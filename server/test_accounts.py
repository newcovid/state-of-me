"""Cross-tenant authorization and native enrollment lifecycle regression tests."""
import json
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from fastapi.testclient import TestClient
from server import app as m
from server import accounts as a

class AccountTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = m.DB, m.CONFIG
        m.DB = str(Path(self.tmp.name)/'db'); m.CONFIG = str(Path(self.tmp.name)/'config.json')
        self.cfg = {'accounts': {'registration':True, 'origin':'https://testserver', 'bot_groups': {'123456': {'token':'s'*32}, '654321': {'token':'t'*32}}}}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.x = TestClient(m.app, base_url='https://testserver', headers={'X-SOM-CSRF':'1'})
        self.y = TestClient(m.app, base_url='https://testserver', headers={'X-SOM-CSRF':'1'})
        self.rx = self.x.post('/v1/accounts/register',json={'name':'alice','password':'password-alice'}).json()['recovery']
        self.y.post('/v1/accounts/register',json={'name':'bob','password':'password-bob'}).raise_for_status()
        self.bot={'Authorization':'Bearer '+'s'*32}
    def tearDown(self):
        self.x.close(); self.y.close(); m.DB,m.CONFIG=self.old; self.tmp.cleanup()
    def native(self, client, name):
        response = client.post('/v1/accounts/login', json={'name': name, 'password': 'password-'+name, 'client': 'native'})
        response.raise_for_status()
        return {'Authorization': 'Bearer '+response.json()['access_token']}
    def pair(self, client, name):
        headers = AccountTests.native(self, client, name)
        body = {'label':'My phone','platform':'android','enrollment_id':uuid.uuid4().hex}
        response = client.post('/v1/accounts/devices/enroll',headers=headers,json=body)
        response.raise_for_status()
        return {'headers':headers,'body':body}, response.json()
    def share(self, client, fields, group=None):
        revision = client.get('/v1/accounts/me').json()['scope_revision']
        body = {'fields': fields, 'expected_revision': revision}
        if group: body['group_id'] = group
        result = client.post('/v1/accounts/groups/scope' if group else '/v1/accounts/public',json=body)
        result.raise_for_status()
        return result
    def send(self, device, app, seq=1):
        return self.x.post('/v1/events',headers={'Authorization':'Bearer '+device['token']},json={'seq':seq,'observed_at':int(time.time()),'app':app})
    def bind(self, client, qq):
        code=client.post('/v1/accounts/bind-code',json={'group_id':'123456','consent':True}).json()['code']
        self.x.post('/v1/bot/bind',headers=self.bot,json={'group_id':'123456','code':code,'qq':qq}).raise_for_status()
        pending=client.get('/v1/accounts/me').json()['pending'][0]
        client.post('/v1/accounts/bind-confirm',json={'id':pending['id']}).raise_for_status()
    def test_full_whatpulse_upload_and_exact_audience_projection(self):
        _, device = self.pair(self.x, 'alice')
        self.bind(self.x, '111111')
        pulse = {'sampled_at': int(time.time()), 'words': 12, 'distance_inches': 1.5,
                 'account_keys': 100000, 'account_download_mb': 12.5,
                 'account_rank_words': 4, 'account_rank_words_formatted': '4th',
                 'realtime_download': '12,3KB/s', 'whatpulse_last_input': '2026-10-25T09:20:00',
                 'profiles': [{'id': 1, 'name': 'Private profile', 'active': True}], 'profiles_truncated': False}
        response = self.x.post('/v1/events', headers={'Authorization': 'Bearer '+device['token']},
                               json={'seq': 1, 'observed_at': int(time.time()), 'usage_summary': pulse})
        self.assertEqual(response.status_code, 200, response.text)
        own = self.x.get('/v1/accounts/status').json()['devices'][0]['usage_summary']
        self.assertEqual(own['account_download_mb'], 12.5)
        self.assertEqual(own['profiles'][0]['name'], 'Private profile')
        self.assertEqual(self.x.get('/v1/public/users/alice').status_code, 404)
        self.share(self.x, ['usage_summary.words', 'usage_summary.realtime_download'])
        public = self.x.get('/v1/public/users/alice').json()['devices'][0]['usage_summary']
        self.assertEqual(public, {'words': 12, 'realtime_download': '12,3KB/s'})
        self.assertNotIn('profiles', public)
        from server import scopes
        self.assertEqual(scopes.project_device({'usage_summary': pulse}, ['usage_summary.account_rank_words'])['usage_summary'], {'account_rank_words': 4})
        self.assertIn('usage_summary.profiles', scopes.KEYS)
        self.assertEqual(len(m.UsageSummary.model_fields), 64)
        # Reject malformed statistics instead of letting structured profile data escape its contract.
        bad = dict(pulse, profiles=[{'name': 'x'*129}])
        response = self.x.post('/v1/events', headers={'Authorization': 'Bearer '+device['token']},
                               json={'seq': 2, 'observed_at': int(time.time()), 'usage_summary': bad})
        self.assertEqual(response.status_code, 422)

    def test_isolation_and_private_default(self):
        _,x=self.pair(self.x,'alice'); _,y=self.pair(self.y,'bob')
        self.assertEqual(self.send(x,'alice-app').status_code,200);self.assertEqual(self.send(y,'bob-app').status_code,200)
        xs=self.x.get('/v1/accounts/status').json()['devices'];ys=self.y.get('/v1/accounts/status').json()['devices']
        self.assertEqual([d['app'] for d in xs],['alice-app']);self.assertEqual([d['app'] for d in ys],['bob-app'])
        self.assertEqual(self.y.post('/v1/accounts/devices/revoke',json={'id':x['device']}).status_code,404)
        self.assertEqual(self.x.get('/v1/public/users/alice').status_code,404)
        self.share(self.x,['app','app_details.label'])
        self.assertEqual([d['app'] for d in self.y.get('/v1/public/users/alice').json()['devices']],['alice-app'])
        self.assertEqual(self.x.get('/v1/status',headers={'Authorization':'Bearer '+'r'*32}).status_code,404)

    def test_account_styles_follow_content_security_policy(self):
        page = self.x.get('/account')
        self.assertIn("style-src 'self'", page.headers['content-security-policy'])
        self.assertNotIn('<style', page.text)
        self.assertIn('.account-page .next-step', self.x.get('/style.css').text)

    def test_entered_group_must_be_connected_before_consent(self):
        response = self.x.post('/v1/accounts/bind-code',json={'group_id':'777777','consent':True})
        self.assertEqual(response.status_code,400)
        self.assertIn('维护者',response.json()['detail'])
        response = self.x.post('/v1/accounts/bind-code',json={'group_id':'654321','consent':True})
        self.assertEqual(response.status_code,200)
        code=response.json()['code']
        # A different group's service credential cannot claim this typed group.
        self.assertEqual(self.x.post('/v1/bot/bind',headers=self.bot,json={'group_id':'654321','code':code,'qq':'333333'}).status_code,401)

    def test_foreground_context_is_group_scoped_and_filtered_with_app(self):
        _,device=self.pair(self.x,'alice')
        details={'label':'Editor','foreground':True,'input_idle':True,'z_order':2,'always_on_top':False,'maximized':True,'fullscreen':False}
        self.x.post('/v1/events',headers={'Authorization':'Bearer '+device['token']},json={'seq':1,'observed_at':time.time(),'app':'editor','app_details':details}).raise_for_status()
        self.bind(self.x,'333333')
        self.share(self.x,['app']+['app_details.'+key for key in details], '123456')
        result=self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()
        self.assertEqual(result['devices'][0]['app_details'],details)
        self.share(self.x,['app','app_details.label'])
        self.assertEqual(self.x.get('/v1/public/users/alice').json()['devices'][0]['app_details'],{'label':'Editor'})
        with m.db() as c:c.execute('UPDATE snapshots SET received=received-2')
        self.x.post('/v1/events',headers={'Authorization':'Bearer '+device['token']},json={'seq':2,'observed_at':time.time(),'app_details':details}).raise_for_status()
        self.assertEqual(self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()['devices'],[])
    def test_native_enrollment_idempotence_and_revocation(self):
        p,d=self.pair(self.x,'alice')
        repeated=self.x.post('/v1/accounts/devices/enroll',headers=p['headers'],json=p['body'])
        repeated.raise_for_status(); self.assertEqual(repeated.json(),d)
        self.send(d,'one').raise_for_status()
        self.x.post('/v1/accounts/devices/revoke',json={'id':d['device']}).raise_for_status()
        self.assertEqual(self.send(d,'two',2).status_code,401)
        self.assertEqual(self.x.post('/v1/accounts/devices/enroll',headers=p['headers'],json=p['body']).status_code,409)
        fresh={**p['body'],'enrollment_id':uuid.uuid4().hex}
        new=self.x.post('/v1/accounts/devices/enroll',headers=p['headers'],json=fresh)
        new.raise_for_status(); self.assertNotEqual(new.json()['token'],d['token'])
        self.assertEqual(self.send(d,'still-revoked',3).status_code,401)
        self.assertEqual(self.x.get('/v1/accounts/status').json()['devices'],[])
    def test_bind_candidate_needs_owner_confirmation_and_group_grant(self):
        _,x=self.pair(self.x,'alice'); self.send(x,'alice').raise_for_status()
        code=self.x.post('/v1/accounts/bind-code',json={'group_id':'123456','consent':True}).json()['code']
        body={'group_id':'123456','code':code,'qq':'333333'}
        self.assertEqual(self.x.post('/v1/bot/bind',headers={'Authorization':'Bearer '+'r'*32},json=body).status_code,401)
        self.x.post('/v1/bot/bind',headers=self.bot,json=body).raise_for_status()
        url='/v1/bot/status/333333?group_id=123456'
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'unbound')
        pending=self.x.get('/v1/accounts/me').json()['pending'][0]
        self.assertEqual(self.y.post('/v1/accounts/bind-confirm',json={'id':pending['id']}).status_code,404)
        self.x.post('/v1/accounts/bind-confirm',json={'id':pending['id']}).raise_for_status()
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'not_shared')
        self.share(self.x,['app'],'123456')
        self.assertEqual(self.x.get(url,headers=self.bot).json()['devices'][0]['app'],'alice')
        self.assertEqual(self.x.get('/v1/bot/status/333333?group_id=654321',headers={'Authorization':'Bearer '+'t'*32}).json()['status'],'not_shared')
        self.x.post('/v1/accounts/groups/revoke',json={'id':'123456'}).raise_for_status()
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'not_shared')
    def test_recovery_rotates_sessions_devices_and_secret(self):
        p,d=self.pair(self.x,'alice');self.send(d,'private').raise_for_status()
        r=self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx})
        self.assertEqual(r.status_code,200);self.assertNotEqual(r.json()['recovery'],self.rx)
        self.assertEqual(self.x.get('/v1/accounts/me').status_code,401)
        self.assertEqual(self.send(d,'private',2).status_code,401)
        self.assertEqual(self.x.get('/v1/accounts/me',headers=p['headers']).status_code,401)
        self.assertEqual(self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx}).status_code,401)
    def test_account_changed_in_other_tab_blocks_scope_mutation(self):
        response=self.x.post('/v1/accounts/public',headers={'X-SOM-Account':'bob'},json={'fields':['app'],'expected_revision':0})
        self.assertEqual(response.status_code,409)
        self.assertFalse(self.x.get('/v1/accounts/me').json()['public'])

    def test_csrf_and_expiry(self):
        self.assertEqual(self.x.post('/v1/accounts/public',headers={'X-SOM-CSRF':'0'},json={'fields':['app'],'expected_revision':0}).status_code,403)
        self.assertEqual(self.x.post('/v1/accounts/public',headers={'Origin':'https://evil.example'},json={'fields':['app'],'expected_revision':0}).status_code,403)
        p,d=self.pair(self.x,'alice')
        with a.connection() as c:c.execute('UPDATE native_sessions SET expires=0')
        self.assertEqual(self.x.get('/v1/accounts/me',headers=p['headers']).status_code,401)
        self.send(d,'collector-survives-management-expiry').raise_for_status()
    def test_static_credentials_rejected_and_unbind_revokes_access(self):
        self.cfg['devices'] = {'phone': {'token':'l'*32, 'apps':['editor']}}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.x.post('/v1/events',headers={'Authorization':'Bearer '+'l'*32},json={'seq':1,'observed_at':int(time.time()),'app':'editor'}).status_code,401)
        url='/v1/bot/status/222222?group_id=123456'
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'unbound')
        self.bind(self.x,'222222')
        self.x.post('/v1/accounts/qq/unbind',json={}).raise_for_status()
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'unbound')

    def test_hashes_only_and_service_token_separate(self):
        _,d=self.pair(self.x,'alice')
        with a.connection() as c:
            hashes=c.execute('SELECT token_hash FROM user_devices').fetchall()
            self.assertNotIn((d['token'],),hashes)
        self.assertEqual(self.x.get('/v1/bot/status/222222?group_id=123456',headers={'Authorization':'Bearer '+'r'*32}).status_code,401)

    def test_credentials_are_not_interchangeable_or_cookie_fallback(self):
        p,d=self.pair(self.x,'alice')
        for token in (d['token'],'som_account_invalid'):
            self.assertEqual(self.x.get('/v1/accounts/me',headers={'Authorization':'Bearer '+token}).status_code,401)
        body={**p['body'],'existing_token':d['token'],'enrollment_id':uuid.uuid4().hex}
        self.assertEqual(self.y.post('/v1/accounts/devices/enroll',headers=self.native(self.y,'bob'),json=body).status_code,409)
        self.assertEqual(self.x.post('/v1/accounts/devices/enroll',json=p['body']).status_code,403)
        self.x.post('/v1/accounts/logout',headers=p['headers'],json={}).raise_for_status()
        self.assertEqual(self.x.get('/v1/accounts/me',headers=p['headers']).status_code,401)
        self.send(d,'collector-survives-logout').raise_for_status()

    def test_scopes_require_revision_and_never_expand_legacy_or_unknown_fields(self):
        self.share(self.x,['device_state.battery_percent'])
        stale=self.x.post('/v1/accounts/public',json={'fields':['app'],'expected_revision':0})
        self.assertEqual(stale.status_code,409)
        self.assertEqual(self.x.post('/v1/accounts/public',json={'enabled':True}).status_code,422)
        self.assertEqual(self.x.post('/v1/accounts/public',json={'fields':['app']}).status_code,422)
        for fields in (['music'],['future.secret'],['app','app']):
            revision=self.x.get('/v1/accounts/me').json()['scope_revision']
            self.assertEqual(self.x.post('/v1/accounts/public',json={'fields':fields,'expected_revision':revision}).status_code,422)
        self.assertEqual(self.x.get('/v1/accounts/me').json()['public_fields'],['device_state.battery_percent'])
        for path in ('/v1/pairings','/v1/pairings/approve','/v1/accounts/groups/usage'):
            self.assertEqual(self.x.post(path,json={}).status_code,404)

    def test_partial_media_respects_scope_and_unknown_position(self):
        _,d=self.pair(self.x,'alice')
        music={'title':'Private title','artist':'Private artist','player':'test.player','playing':True,'playback_status':'playing','position_ms':0,'position_known':False}
        self.x.post('/v1/events',headers={'Authorization':'Bearer '+d['token']},json={'seq':1,'observed_at':time.time(),'music':music}).raise_for_status()
        self.share(self.x,['music.position_ms'])
        item=self.x.get('/v1/public/users/alice').json()['devices'][0]
        self.assertNotIn('device_label',item)
        self.assertEqual(set(item['music']),{'position_ms','playing','playback_status','position_known'})
        self.assertFalse(item['music']['position_known'])
        self.share(self.x,['music.title'])
        self.assertEqual(self.x.get('/v1/public/users/alice').json()['devices'][0]['music']['title'],'Private title')
        with m.db() as c:c.execute('UPDATE snapshots SET received=received-2')
        music.update(playing=False,playback_status='paused')
        self.x.post('/v1/events',headers={'Authorization':'Bearer '+d['token']},json={'seq':2,'observed_at':time.time(),'music':music}).raise_for_status()
        self.assertEqual(self.x.get('/v1/public/users/alice').json()['devices'],[])

    def test_group_revocation_invalidates_stale_scope_editor(self):
        self.bind(self.x,'333333'); self.share(self.x,['app'],'123456')
        revision=self.x.get('/v1/accounts/me').json()['scope_revision']
        self.x.post('/v1/accounts/groups/revoke',json={'id':'123456'}).raise_for_status()
        self.assertEqual(self.x.post('/v1/accounts/groups/scope',json={'group_id':'123456','fields':['app'],'expected_revision':revision}).status_code,409)

    def test_account_snapshot_pairs_scope_with_its_original_revision(self):
        from unittest.mock import patch
        self.share(self.x,['app','music.title'])
        before=self.x.get('/v1/accounts/me').json()['scope_revision']
        original=a.revision; changed=False
        def revoke_between_reads(connection,uid):
            nonlocal changed
            if not changed:
                changed=True
                with a.connection() as writer:
                    writer.execute('UPDATE users SET public_fields=? WHERE id=?',(json.dumps(['app']),uid))
                    writer.execute('UPDATE account_revisions SET revision=revision+1 WHERE uid=?',(uid,))
            return original(connection,uid)
        with patch.object(a,'revision',revoke_between_reads):
            snapshot=self.x.get('/v1/accounts/me').json()
        self.assertEqual(snapshot['public_fields'],['app','music.title'])
        self.assertEqual(snapshot['scope_revision'],before)
        self.assertEqual(self.x.post('/v1/accounts/public',json={'fields':snapshot['public_fields'],'expected_revision':snapshot['scope_revision']}).status_code,409)
        self.assertEqual(self.x.get('/v1/accounts/me').json()['public_fields'],['app'])

    def test_recovery_during_login_does_not_issue_old_password_session(self):
        from unittest.mock import patch
        original=a.password_hash; changed=False
        def recover_after_password_calculation(password,salt):
            nonlocal changed
            hashed=original(password,salt)
            if password=='password-alice' and not changed:
                changed=True
                self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx}).raise_for_status()
            return hashed
        with patch.object(a,'password_hash',recover_after_password_calculation):
            response=self.x.post('/v1/accounts/login',json={'name':'alice','password':'password-alice','client':'native'})
        self.assertEqual(response.status_code,401)
        self.assertNotIn('access_token',response.json())
        with a.connection() as c:self.assertEqual(c.execute('SELECT COUNT(*) FROM native_sessions').fetchone()[0],0)

    def test_recovery_between_authorization_and_enrollment_cannot_restore_device(self):
        from unittest.mock import patch
        headers=self.native(self.x,'alice'); original=a.user; changed=False
        def recover_after_authorization(request,mutation=False,*args,**kwargs):
            nonlocal changed
            result=original(request,mutation,*args,**kwargs)
            if request.url.path=='/v1/accounts/devices/enroll' and not changed:
                changed=True
                self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx}).raise_for_status()
            return result
        with patch.object(a,'user',recover_after_authorization):
            response=self.x.post('/v1/accounts/devices/enroll',headers=headers,json={'label':'PC','platform':'windows','enrollment_id':uuid.uuid4().hex})
        self.assertEqual(response.status_code,401)
        with a.connection() as c:self.assertEqual(c.execute('SELECT COUNT(*) FROM user_devices').fetchone()[0],0)

if __name__=='__main__':unittest.main()

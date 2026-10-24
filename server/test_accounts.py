"""Cross-tenant authorization and pairing lifecycle regression tests."""
import json
import tempfile
import time
import unittest
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
    def pair(self, client, name):
        pair=client.post('/v1/pairings',json={'label':'My phone','platform':'android'}).json()
        self.assertEqual(client.post('/v1/pairings/approve',json={'code':pair['code'],'account':name}).status_code,200)
        r=client.post('/v1/pairings/poll',json={'code':pair['code'],'secret':pair['secret']}).json()
        return pair,r
    def send(self, device, app, seq=1):
        return self.x.post('/v1/events',headers={'Authorization':'Bearer '+device['token']},json={'seq':seq,'observed_at':int(time.time()),'app':app})
    def bind(self, client, qq):
        code=client.post('/v1/accounts/bind-code',json={'group_id':'123456','consent':True}).json()['code']
        self.x.post('/v1/bot/bind',headers=self.bot,json={'group_id':'123456','code':code,'qq':qq}).raise_for_status()
        pending=client.get('/v1/accounts/me').json()['pending'][0]
        client.post('/v1/accounts/bind-confirm',json={'id':pending['id']}).raise_for_status()
    def test_isolation_and_private_default(self):
        _,x=self.pair(self.x,'alice'); _,y=self.pair(self.y,'bob')
        self.assertEqual(self.send(x,'alice-app').status_code,200);self.assertEqual(self.send(y,'bob-app').status_code,200)
        xs=self.x.get('/v1/accounts/status').json()['devices'];ys=self.y.get('/v1/accounts/status').json()['devices']
        self.assertEqual([d['app'] for d in xs],['alice-app']);self.assertEqual([d['app'] for d in ys],['bob-app'])
        self.assertEqual(self.y.post('/v1/accounts/devices/revoke',json={'id':x['device']}).status_code,404)
        self.assertEqual(self.x.get('/v1/public/users/alice').status_code,404)
        self.x.post('/v1/accounts/public',json={'enabled':True}).raise_for_status()
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
        result=self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()
        self.assertEqual(result['devices'][0]['app_details'],details)
        self.x.post('/v1/accounts/public',json={'enabled':True}).raise_for_status()
        self.assertEqual(self.x.get('/v1/public/users/alice').json()['devices'][0]['app_details'],{'label':'Editor'})
        with m.db() as c:c.execute('UPDATE snapshots SET received=received-2')
        self.x.post('/v1/events',headers={'Authorization':'Bearer '+device['token']},json={'seq':2,'observed_at':time.time(),'app_details':details}).raise_for_status()
        self.assertEqual(self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()['devices'],[])
    def test_pair_secret_account_and_revocation(self):
        p=self.x.post('/v1/pairings',json={'label':'Laptop','platform':'windows'}).json()
        self.assertEqual(self.x.post('/v1/pairings/approve',json={'code':p['code'],'account':'bob'}).status_code,409)
        self.assertEqual(self.x.post('/v1/pairings/poll',json={'code':p['code'],'secret':'wrong'*8}).status_code,404)
        self.x.post('/v1/pairings/approve',json={'code':p['code'],'account':'alice'}).raise_for_status()
        self.assertEqual(self.y.post('/v1/pairings/approve',json={'code':p['code'],'account':'bob'}).status_code,409)
        d=self.x.post('/v1/pairings/poll',json={'code':p['code'],'secret':p['secret']}).json()
        self.send(d,'one').raise_for_status()
        self.x.post('/v1/accounts/devices/revoke',json={'id':d['device']}).raise_for_status()
        self.assertEqual(self.send(d,'two',2).status_code,401)
        self.assertEqual(self.x.post('/v1/pairings/poll',json={'code':p['code'],'secret':p['secret']}).status_code,404)
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
        self.assertEqual(self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx}).status_code,401)
    def test_account_changed_in_other_tab_blocks_scope_mutation(self):
        response=self.x.post('/v1/accounts/public',headers={'X-SOM-Account':'bob'},json={'enabled':True})
        self.assertEqual(response.status_code,409)
        self.assertFalse(self.x.get('/v1/accounts/me').json()['public'])

    def test_csrf_and_expiry(self):
        self.assertEqual(self.x.post('/v1/accounts/public',headers={'X-SOM-CSRF':'0'},json={'enabled':True}).status_code,403)
        self.assertEqual(self.x.post('/v1/accounts/public',headers={'Origin':'https://evil.example'},json={'enabled':True}).status_code,403)
        p=self.x.post('/v1/pairings',json={'label':'Phone','platform':'android'}).json()
        with a.connection() as c:c.execute('UPDATE pairings SET expires=0')
        self.assertEqual(self.x.post('/v1/pairings/approve',json={'code':p['code'],'account':'alice'}).status_code,404)
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

if __name__=='__main__':unittest.main()

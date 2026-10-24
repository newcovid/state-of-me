import time
from datetime import datetime, timezone
import unittest
from server import test_accounts

class UsageTests(unittest.TestCase):
    setUp = test_accounts.AccountTests.setUp
    tearDown = test_accounts.AccountTests.tearDown
    pair = test_accounts.AccountTests.pair
    bind = test_accounts.AccountTests.bind
    share = test_accounts.AccountTests.share
    def sample(self):
        return {'timezone':'UTC','sampled_at':int(time.time()),'first_event_at':int(time.time())-3600,
                'days':[{'date':datetime.now(timezone.utc).date().isoformat(),'screen_ms':60000,'unlocked_ms':30000,'known_ms':60000,'unlocks':1,'hourly_screen_ms':[60000]+[0]*23,'apps':[{'package':'editor','label':'Editor','foreground_ms':30000,'sessions':1}]}]}

    def test_history_is_private_and_survives_presence_expiry(self):
        _,d=self.pair(self.x,'alice');h={'Authorization':'Bearer '+d['token']}
        self.x.post('/v1/usage',headers=h,json=self.sample()).raise_for_status()
        self.assertEqual(len(self.x.get('/v1/accounts/usage').json()['devices']),1)
        self.assertEqual(self.y.get('/v1/accounts/usage').json()['devices'],[])
        self.bind(self.x,'333333');url='/v1/bot/usage/333333?group_id=123456&days=1'
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'not_shared')
        self.share(self.x,['usage_history.summary','usage_history.apps'],'123456')
        self.assertEqual(len(self.x.get(url,headers=self.bot).json()['devices']),1)
        self.assertEqual(self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()['devices'],[])
        self.share(self.x,['app'])
        self.assertEqual(self.x.get('/v1/public/users/alice').json()['history'],[])
        self.share(self.x,[],'123456')
        self.assertEqual(self.x.get(url,headers=self.bot).json()['status'],'not_shared')
        self.x.post('/v1/usage',headers=h,json={'clear':True}).raise_for_status()
        self.assertEqual(self.x.get('/v1/accounts/usage').json()['devices'],[])

    def test_replacement_validation_and_device_revocation(self):
        _,d=self.pair(self.x,'alice');h={'Authorization':'Bearer '+d['token']};sample=self.sample()
        self.x.post('/v1/usage',headers=h,json=sample).raise_for_status()
        self.x.post('/v1/usage',headers=h,json=sample).raise_for_status()
        self.assertEqual(len(self.x.get('/v1/accounts/usage').json()['devices'][0]['days']),1)
        sample['sampled_at']-=10
        self.assertEqual(self.x.post('/v1/usage',headers=h,json=sample).status_code,409)
        sample=self.sample();sample['days'][0]['unlocked_ms']=999999
        self.assertEqual(self.x.post('/v1/usage',headers=h,json=sample).status_code,422)
        sample=self.sample();sample['timezone']='bad-zone'
        self.assertEqual(self.x.post('/v1/usage',headers=h,json=sample).status_code,422)
        self.assertEqual(self.x.get('/v1/accounts/usage?days=100').status_code,422)
        self.x.post('/v1/accounts/devices/revoke',json={'id':d['device']}).raise_for_status()
        self.assertEqual(self.x.get('/v1/accounts/usage').json()['devices'],[])
        self.assertEqual(self.x.post('/v1/usage',headers=h,json={'clear':True}).status_code,401)

    def test_each_history_scope_is_independent_for_public_and_bot(self):
        _,d=self.pair(self.x,'alice')
        self.x.post('/v1/usage',headers={'Authorization':'Bearer '+d['token']},json=self.sample()).raise_for_status()
        self.bind(self.x,'333333')
        for scope,expected in [('summary',{'screen_ms','unlocked_ms','unlocks'}),('apps',{'apps','apps_truncated'}),('hourly',{'hourly_screen_ms'})]:
            with self.subTest(scope=scope):
                self.share(self.x,['usage_history.'+scope])
                public=self.x.get('/v1/public/users/alice').json()
                self.assertEqual(public['devices'],[])
                device=public['history'][0]; self.assertNotIn('device_label',device)
                self.assertEqual(set(device['days'][0])-{'date','known_ms'},expected)
                self.share(self.x,['usage_history.'+scope],'123456')
                device=self.x.get('/v1/bot/usage/333333?group_id=123456&days=1',headers=self.bot).json()['devices'][0]
                if scope=='hourly':self.assertEqual(device['days'][0]['hourly_screen_ms'],self.sample()['days'][0]['hourly_screen_ms'])
                if scope=='apps':self.assertEqual(device['app_ranking'][0]['package'],'editor')
                else:self.assertNotIn('app_ranking',device)
                if scope!='summary':self.assertNotIn('screen_ms',device['days'][0])

    def test_recovery_deletes_historical_records_and_revokes_upload(self):
        p,d=self.pair(self.x,'alice')
        headers={'Authorization':'Bearer '+d['token']}
        self.x.post('/v1/usage',headers=headers,json=self.sample()).raise_for_status()
        self.y.post('/v1/accounts/recover',json={'name':'alice','password':'new-password-alice','recovery':self.rx}).raise_for_status()
        from server import accounts
        with accounts.connection() as c:self.assertEqual(c.execute('SELECT COUNT(*) FROM usage_history').fetchone()[0],0)
        self.assertEqual(self.x.post('/v1/usage',headers=headers,json=self.sample()).status_code,401)

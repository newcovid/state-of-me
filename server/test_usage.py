import time
from datetime import datetime, timezone
import unittest
from server import test_accounts

class UsageTests(unittest.TestCase):
    setUp = test_accounts.AccountTests.setUp
    tearDown = test_accounts.AccountTests.tearDown
    pair = test_accounts.AccountTests.pair
    bind = test_accounts.AccountTests.bind
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
        self.x.post('/v1/accounts/groups/usage',json={'group_id':'123456','enabled':True}).raise_for_status()
        self.assertEqual(len(self.x.get(url,headers=self.bot).json()['devices']),1)
        self.assertEqual(self.x.get('/v1/bot/status/333333?group_id=123456',headers=self.bot).json()['devices'],[])
        self.x.post('/v1/accounts/public',json={'enabled':True}).raise_for_status()
        self.assertNotIn('usage_history',self.x.get('/v1/public/users/alice').text)
        self.x.post('/v1/accounts/groups/usage',json={'group_id':'123456','enabled':False}).raise_for_status()
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

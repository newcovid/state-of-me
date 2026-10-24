import json
import tempfile
import time
import unittest
from unittest.mock import patch
from pathlib import Path
from fastapi.testclient import TestClient
from server import app as m


class ContractTests(unittest.TestCase):
    def test_twenty_second_heartbeat_progress_and_expiry(self):
        self.cfg['ttl_seconds'] = 45
        self.cfg['readers']['viewer']['fields'] += ['lyric', 'lyric_window']
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        music = {'title': 'Song', 'artist': 'Artist', 'player': 'player',
                 'playing': True, 'position_ms': 0, 'duration_ms': 180000,
                 'lyric_status': 'synced', 'playback_rate': 1,
                 'lyric_window': {'start_ms': 0, 'end_ms': 60000,
                                  'lines': [{'at_ms': 0, 'text': 'first'},
                                            {'at_ms': 15000, 'text': 'second'}]}}
        with patch.object(m.time, 'time', return_value=1000000):
            self.assertEqual(self.send(music=music).status_code, 200)
        with patch.object(m.time, 'time', return_value=1000020):
            item = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]
            self.assertEqual(item['music']['position_ms'], 20000)
            self.assertEqual(item['music']['lyric'], 'second')
            self.assertEqual(item['expires_in'], 25)
            # A full snapshot without music retracts playback immediately.
            self.assertEqual(self.send(2, app='editor').status_code, 200)
            item = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]
            self.assertNotIn('music', item)
        with patch.object(m.time, 'time', return_value=1000066):
            self.assertEqual(self.client.get('/v1/status', headers=self.rh).json()['devices'], [])

    def test_desktop_categories_scoping_expiry_and_retraction(self):
        now = int(time.time())
        fields = ['presence', 'manual_status', 'display', 'usage_summary']
        self.cfg['devices']['phone'].update({f: True for f in fields})
        self.cfg['readers']['viewer']['fields'] += fields
        self.cfg['readers']['viewer']['usage_summary_fields'] = ['keys', 'scope']
        self.cfg['public'] = {'enabled': True, 'devices': ['phone'], 'fields': ['app']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        data = dict(presence={'state': 'idle', 'idle_seconds': 200},
                    manual_status={'text': 'Focus', 'expires_at': now + 2},
                    display={'monitor_count': 2, 'fullscreen': True},
                    usage_summary={'sampled_at': now, 'keys': 42, 'upload': 123})
        self.assertEqual(self.send(**data).status_code, 200)
        item = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]
        self.assertEqual(item['usage_summary'], {'scope': 'since_pulse', 'keys': 42})
        self.assertEqual(self.client.get('/v1/public/status').json()['devices'], [])
        with patch.object(m.time, 'time', return_value=now + 3):
            self.assertNotIn('manual_status', self.client.get('/v1/status', headers=self.rh).json()['devices'][0])
        self.cfg['devices']['phone']['usage_summary'] = False
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertNotIn('usage_summary', self.client.get('/v1/status', headers=self.rh).json()['devices'][0])
        self.assertEqual(self.send(2, hidden=True, **data).status_code, 200)
        self.assertEqual(self.client.get('/v1/status', headers=self.rh).json()['devices'], [])

    def test_desktop_invalid_values_and_stale_statistics(self):
        self.assertEqual(self.send(resources={'cpu_percent': 101}).status_code, 422)
        self.assertEqual(self.send(usage_summary={'sampled_at': 0, 'keys': -1}).status_code, 422)
        self.cfg['devices']['phone']['usage_summary'] = True
        self.cfg['readers']['viewer']['fields'] += ['usage_summary']
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.send(usage_summary={'sampled_at': int(time.time())-60, 'keys': 1}).status_code, 200)
        self.assertEqual(self.client.get('/v1/status', headers=self.rh).json()['devices'], [])

    def test_unknown_media_position_does_not_advance(self):
        with patch.object(m.time, 'time', return_value=1000000):
            self.assertEqual(self.send(music={'title': 'Live', 'player': 'player', 'playing': True,
                             'media_kind': 'video', 'position_known': False, 'position_ms': 1000}).status_code, 200)
        with patch.object(m.time, 'time', return_value=1000005):
            music = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]['music']
            self.assertEqual(music['position_ms'], 1000)
            self.assertEqual(music['media_kind'], 'video')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        m.DB = str(Path(self.tmp.name)/'test.db')
        m.CONFIG = str(Path(self.tmp.name)/'config.json')
        self.cfg = {'ttl_seconds': 10, 'devices': {'phone': {'token':'w'*32,'apps':['editor'],'players':['player'],'lyrics':True}}, 'readers': {'viewer':{'token':'r'*32,'devices':['phone'],'fields':['app','music']}}, 'public': {'enabled':False}}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.client = TestClient(m.app)
        self.wh = {'Authorization':'Bearer '+'w'*32}
        self.rh = {'Authorization':'Bearer '+'r'*32}

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def send(self, seq=1, **kw):
        return self.client.post('/v1/events',headers=self.wh,json={'seq':seq,'observed_at':time.time(),**kw})

    def test_context_categories_scopes_retraction_and_hidden(self):
        payload = {'network': {'connected': True, 'transports': ['wifi']},
                   'audio': {'ringer_mode': 'silent'}, 'resources': {'thermal_status': 2},
                   'collector_health': {'version': '0.7.0', 'usage_access': False},
                   'app_details': {'label': 'Editor', 'active_for_ms': 1000}}
        for field in payload:
            self.cfg['devices']['phone'][field] = True
        self.cfg['readers']['viewer']['fields'] += list(payload)
        self.cfg['public'] = {'enabled': True, 'devices': ['phone'], 'fields': ['music', 'lyric']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.send(app='editor', **payload).status_code, 200)
        item = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]
        for field in payload:
            self.assertIn(field, item)
        self.assertEqual(self.client.get('/v1/public/status').json()['devices'], [])
        self.cfg['devices']['phone']['network'] = False
        self.cfg['devices']['phone']['apps'] = []
        self.cfg['readers']['viewer']['fields'].remove('audio')
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        item = self.client.get('/v1/status', headers=self.rh).json()['devices'][0]
        for field in ('network', 'audio', 'app_details'):
            self.assertNotIn(field, item)
        self.assertEqual(self.send(2, hidden=True, **payload).status_code, 200)
        self.assertEqual(self.client.get('/v1/status', headers=self.rh).json()['devices'], [])
        with m.db() as conn:
            stored = json.loads(conn.execute('SELECT payload FROM snapshots').fetchone()[0])
        for field in payload:
            self.assertIsNone(stored[field])

    def test_context_default_denial_and_validation(self):
        self.assertEqual(self.send(network={'transports': ['secret-ssid']}).status_code, 422)
        self.assertEqual(self.send(resources={'thermal_status': 7}).status_code, 422)
        self.assertEqual(self.send(device_state={'battery_temperature_c': 101}).status_code, 422)
        self.assertEqual(self.send(network={'connected': True}, app_details={'label': 'Secret'}).status_code, 200)
        with m.db() as conn:
            stored = json.loads(conn.execute('SELECT payload FROM snapshots').fetchone()[0])
        self.assertIsNone(stored['network'])
        self.assertIsNone(stored['app_details'])

    def test_filter_modes_and_live_retraction(self):
        for category in ('apps', 'players'):
            self.assertFalse(m.permitted({}, category, 'app'))
            self.assertTrue(m.permitted({category: ['app']}, category, 'app'))
            self.assertFalse(m.permitted({category+'_mode': 'off', category: ['app']}, category, 'app'))
            self.assertFalse(m.permitted({category+'_mode': 'invalid'}, category, 'app'))
            self.assertTrue(m.permitted({category+'_mode': 'blacklist'}, category, 'app'))
            self.assertFalse(m.permitted({category+'_mode': 'blacklist',category+'_blocked': ['app']}, category, 'app'))
        rule = self.cfg['devices']['phone']
        rule.update(apps_mode='blacklist', players_mode='blacklist', apps_blocked=['secret'], players_blocked=['secret'])
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.send(app='new-app',music={'title':'Song','player':'new-player','playing':True}).status_code,200)
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'][0]['app'],'new-app')
        rule.update(apps_blocked=['new-app'], players_blocked=['new-player'])
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])

    def test_pause_repeated_heartbeat_and_resume(self):
        self.cfg['public']={'enabled':True,'devices':['phone'],'fields':['music']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        for seq,playing in enumerate((True,False,False,True),1):
            with m.db() as conn:
                conn.execute('UPDATE snapshots SET received=?',(time.time()-2,))
            self.assertEqual(self.send(seq,music={'title':'Song','player':'player','playing':playing,
                'playback_status':'playing' if playing else 'paused'}).status_code,200)
            for path,headers in (('/v1/status',self.rh),('/v1/public/status',{})):
                devices=self.client.get(path,headers=headers).json()['devices']
                self.assertEqual(bool(devices),playing)

    def test_old_paused_snapshot_is_retracted_without_new_upload(self):
        self.send(app='editor')
        with m.db() as conn:
            old={'hidden':False,'app':'editor','music':{'title':'Old','player':'player',
                 'playing':False,'position_ms':100,'duration_ms':1000}}
            conn.execute('UPDATE snapshots SET payload=?',(json.dumps(old),))
        device=self.client.get('/v1/status',headers=self.rh).json()['devices'][0]
        self.assertEqual(device['app'],'editor')
        self.assertNotIn('music',device)

    def test_explicit_nonplaying_states_override_playing_flag(self):
        for state in ('paused','stopped','buffering','connecting','error'):
            self.assertFalse(m.active_music({'playing':True,'playback_status':state}))
        self.assertTrue(m.active_music({'playing':True}))
        self.assertFalse(m.active_music({'playing':False}))

    def test_public_subfield_scopes_hide_diagnostics(self):
        self.cfg['devices']['phone'].update(device_state=True,app_details=True)
        self.cfg['public']={'enabled':True,'devices':['phone'],'fields':['app','app_details','device_state'],
                           'device_state_fields':['battery_percent','charging','locked'], 'app_details_fields':['label']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.send(app='editor',app_details={'label':'Editor','active_for_ms':1234},
                  device_state={'battery_percent':80,'charging':True,'locked':False,'battery_temperature_c':35,'power_save':True})
        item=self.client.get('/v1/public/status').json()['devices'][0]
        self.assertEqual(set(item['device_state']),{'battery_percent','charging','locked'})
        self.assertEqual(item['app_details'],{'label':'Editor'})
        self.cfg['public']['device_state_fields']=[]
        self.cfg['public']['app_details_fields']=[]
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        item=self.client.get('/v1/public/status').json()['devices'][0]
        self.assertNotIn('device_state',item)
        self.assertNotIn('app_details',item)

    def test_scope_replay_and_clear(self):
        self.assertEqual(self.client.get('/v1/status',headers=self.wh).status_code,401)
        self.assertEqual(self.client.post('/v1/events',headers=self.rh,json={'seq':1,'observed_at':time.time()}).status_code,401)
        self.assertEqual(self.send(app='editor').status_code,200)
        self.assertEqual(self.send(app='editor').status_code,409)
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'][0]['app'],'editor')
        self.assertEqual(self.send(2,hidden=True,app='editor').status_code,200)
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])

    def test_filters_and_lyrics_audience(self):
        self.send(app='secret',music={'title':'Song','player':'player','playing':True,'lyric':'private line'})
        d=self.client.get('/v1/status',headers=self.rh).json()['devices'][0]
        self.assertNotIn('app',d)
        self.assertIsNone(d['music']['lyric'])
        self.cfg['devices']['phone']['players']=[]
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])

    def test_ttl_and_public_default(self):
        self.send(app='editor')
        with m.db() as c:c.execute('UPDATE snapshots SET received=?',(time.time()-11,))
        r=self.client.get('/v1/status',headers=self.rh)
        self.assertEqual(r.json()['devices'],[])
        self.assertEqual(r.headers['cache-control'],'no-store')
        self.assertEqual(self.client.get('/v1/public/status').status_code,404)

    def test_invalid_and_stale(self):
        self.assertEqual(self.send(observed_at_bad=1).status_code,422)
        self.assertEqual(self.client.post('/v1/events',headers=self.wh,json={'seq':1,'observed_at':0}).status_code,422)

    def test_device_state_scope_and_retraction(self):
        self.cfg['devices']['phone']['device_state']=True
        self.cfg['readers']['viewer']['fields'].append('device_state')
        self.cfg['public']={'enabled':True,'devices':['phone'],'fields':['music','lyric','lyric_window']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.send(device_state={'battery_percent':101}).status_code,422)
        self.assertEqual(self.send(device_state={'battery_percent':72,'locked':True,'charging':False}).status_code,200)
        data=self.client.get('/v1/status',headers=self.rh).json()['devices'][0]
        self.assertEqual(data['device_state']['battery_percent'],72)
        self.assertEqual(self.client.get('/v1/public/status').json()['devices'],[])
        self.cfg['devices']['phone']['device_state']=False
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])

    def test_hidden_device_state_cleared(self):
        self.cfg['devices']['phone']['device_state']=True
        self.cfg['readers']['viewer']['fields'].append('device_state')
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.send(hidden=True,device_state={'battery_percent':50})
        with m.db() as c:
            payload=json.loads(c.execute('SELECT payload FROM snapshots').fetchone()[0])
        self.assertIsNone(payload['device_state'])

    def test_public_music_does_not_expose_private_fields(self):
        self.cfg['public']={'enabled':True,'devices':['phone'],'fields':['music']}
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.send(app='editor',music={'title':'Sample','player':'player','playing':True,'lyric':'private line',
            'lyric_window':{'start_ms':0,'end_ms':10000,'lines':[{'at_ms':0,'text':'private line'}]}})
        item=self.client.get('/v1/public/status').json()['devices'][0]
        self.assertNotIn('app',item)
        self.assertIsNone(item['music']['lyric'])
        self.assertIsNone(item['music']['lyric_window'])

    def test_window_projection_and_revocation(self):
        self.cfg['readers']['viewer']['fields'] += ['lyric', 'lyric_window']
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        music = {'title':'Song', 'player':'player', 'playing':True, 'duration_ms':100000,
                 'lyric_status':'synced', 'lyric_window':{'start_ms':0,'end_ms':60000,
                 'lines':[{'at_ms':0,'text':'first'}, {'at_ms':2000,'text':'next'}]}}
        self.assertEqual(self.send(music=music).status_code,200)
        with m.db() as c:c.execute('UPDATE snapshots SET received=?',(time.time()-3,))
        def read():return self.client.get('/v1/status',headers=self.rh).json()['devices'][0]['music']
        self.assertEqual(read()['lyric'],'next')
        self.assertIsNotNone(read()['lyric_window'])
        self.cfg['readers']['viewer']['fields'].remove('lyric_window')
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertEqual(read()['lyric'],'next')
        self.assertIsNone(read()['lyric_window'])
        self.cfg['devices']['phone']['lyrics']=False
        Path(m.CONFIG).write_text(json.dumps(self.cfg))
        self.assertIsNone(read()['lyric'])
        self.assertIsNone(read()['lyric_window'])

    def test_window_validation_and_paused_suppression(self):
        base={'title':'Song','player':'player','position_ms':500,'playing':False}
        bad={'start_ms':1000,'end_ms':500,'lines':[]}
        self.assertEqual(self.send(music={**base,'lyric_window':bad}).status_code,422)
        self.assertEqual(self.send(music={**base,'playback_rate':9}).status_code,422)
        self.assertEqual(self.send(music=base).status_code,200)
        with m.db() as c:c.execute('UPDATE snapshots SET received=?',(time.time()-3,))
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])


if __name__ == '__main__':unittest.main()

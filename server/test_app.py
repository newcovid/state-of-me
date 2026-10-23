import json
import tempfile
import time
import unittest
from pathlib import Path
from fastapi.testclient import TestClient
from server import app as m


class ContractTests(unittest.TestCase):
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

    def test_scope_replay_and_clear(self):
        self.assertEqual(self.client.get('/v1/status',headers=self.wh).status_code,401)
        self.assertEqual(self.client.post('/v1/events',headers=self.rh,json={'seq':1,'observed_at':time.time()}).status_code,401)
        self.assertEqual(self.send(app='editor').status_code,200)
        self.assertEqual(self.send(app='editor').status_code,409)
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'][0]['app'],'editor')
        self.assertEqual(self.send(2,hidden=True,app='editor').status_code,200)
        self.assertEqual(self.client.get('/v1/status',headers=self.rh).json()['devices'],[])

    def test_filters_and_lyrics_audience(self):
        self.send(app='secret',music={'title':'Song','player':'player','lyric':'private line'})
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


if __name__ == '__main__':unittest.main()

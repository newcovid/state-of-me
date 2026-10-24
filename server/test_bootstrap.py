"""The documented initializer must produce a fresh, isolated usable instance."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from fastapi.testclient import TestClient
from server import app as m
from deploy.init_config import configurations

class BootstrapTests(unittest.TestCase):
    def test_new_instance_and_paired_bot_config(self):
        server, bot = configurations('https://status.example.com/presence/', ['123456789'])
        self.assertEqual(server['accounts']['origin'], 'https://status.example.com')
        self.assertEqual(bot['url'], 'https://status.example.com/presence')
        self.assertEqual(server['devices'], {});self.assertEqual(server['readers'], {})
        self.assertEqual(server['accounts']['bot_groups']['123456789']['token'],bot['groups']['123456789']['bot_token'])
        with tempfile.TemporaryDirectory() as tmp:
            old=m.DB,m.CONFIG
            try:
                m.DB=str(Path(tmp)/'db');m.CONFIG=str(Path(tmp)/'config.json');Path(m.CONFIG).write_text(json.dumps(server))
                with TestClient(m.app,base_url='https://status.example.com',headers={'X-SOM-CSRF':'1'}) as client:
                    client.post('/v1/accounts/register',json={'name':'new-user','password':'fresh-instance-pass'}).raise_for_status()
                    me=client.get('/v1/accounts/me').json();self.assertEqual(me['devices'],[]);self.assertFalse(me['public'])
                    self.assertEqual(client.get('/v1/public/users/new-user').status_code,404)
            finally:m.DB,m.CONFIG=old
    def test_initializer_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            cmd=[sys.executable,'deploy/init_config.py','--url','https://example.com','--output',tmp]
            self.assertEqual(subprocess.run(cmd,capture_output=True).returncode,0)
            before=(Path(tmp)/'config.json').read_bytes()
            self.assertNotEqual(subprocess.run(cmd,capture_output=True).returncode,0)
            self.assertEqual((Path(tmp)/'config.json').read_bytes(),before)
    def test_invalid_url_and_group(self):
        for url in ('http://example.com','https://user:password@example.com','https://example.com/?secret=x'):
            with self.assertRaises(ValueError):configurations(url)
        with self.assertRaises(ValueError):configurations('https://example.com',['not-a-group'])

if __name__=='__main__':unittest.main()

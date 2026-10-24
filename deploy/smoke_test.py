"""Exercise a disposable localhost instance created by init_config.py, never production."""
import json
import time
import uuid
from pathlib import Path
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:8080'
ACCOUNT_TOKEN = ''


def request(path, body=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json', 'X-SOM-CSRF': '1', 'Origin': 'https://status.example.com'}
    if path.startswith('/v1/accounts/') and ACCOUNT_TOKEN and token is None:
        token = ACCOUNT_TOKEN
        headers['X-SOM-Account'] = 'smoke-user'
    if token:
        headers['Authorization'] = 'Bearer '+token
    req = urllib.request.Request(BASE+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (path, response.status, expected)
        return json.load(response)


def main():
    global ACCOUNT_TOKEN
    for attempt in range(60):
        try:
            if request('/health')['ok']:
                break
        except (OSError, AssertionError):
            if attempt == 59:
                raise
            time.sleep(1)
    config = json.loads(Path('deploy/config.json').read_text())
    assert 'devices' not in config and 'readers' not in config
    account = request('/v1/accounts/register', {'name': 'smoke-user', 'password': 'disposable-test-password', 'client': 'native'})
    ACCOUNT_TOKEN = account['access_token']
    assert not request('/v1/accounts/me')['public']
    enrollment = {'label': 'Disposable smoke device', 'platform': 'android', 'existing_token': '', 'enrollment_id': str(uuid.uuid4())}
    device = request('/v1/accounts/devices/enroll', enrollment)
    assert request('/v1/accounts/devices/enroll', enrollment)['device'] == device['device']
    request('/v1/events', {'seq': 1, 'observed_at': time.time(), 'app': 'example-editor'}, device['token'])
    assert request('/v1/accounts/status')['devices'][0]['app'] == 'example-editor'
    request('/v1/public/users/smoke-user', expected=404)
    group = '123456789'
    token = config['accounts']['bot_groups'][group]['token']
    code = request('/v1/accounts/bind-code', {'group_id': group, 'consent': True})['code']
    request('/v1/bot/bind', {'group_id': group, 'code': code, 'qq': '111111111'}, token)
    assert request('/v1/bot/status/111111111?group_id='+group, token=token)['status'] == 'unbound'
    pending = request('/v1/accounts/me')['pending'][0]
    request('/v1/accounts/bind-confirm', {'id': pending['id']})
    me = request('/v1/accounts/me')
    assert me['group_scopes'][group] == []
    request('/v1/accounts/groups/scope', {'group_id': group, 'fields': ['app'], 'expected_revision': me['scope_revision']})
    assert request('/v1/bot/status/111111111?group_id='+group, token=token)['devices'][0]['app'] == 'example-editor'
    me = request('/v1/accounts/me')
    request('/v1/accounts/public', {'fields': ['app'], 'expected_revision': me['scope_revision']})
    assert request('/v1/public/users/smoke-user')['devices'][0]['app'] == 'example-editor'
    request('/v1/accounts/public', {'fields': [], 'expected_revision': me['scope_revision']}, expected=409)
    request('/v1/accounts/devices/revoke', {'id': device['device']})
    request('/v1/events', {'seq': 2, 'observed_at': time.time(), 'app': 'example-editor'}, device['token'], expected=401)
    request('/v1/accounts/devices/enroll', enrollment, expected=409)
    request('/v1/accounts/logout', {})
    request('/v1/accounts/me', expected=401)
    print('Clean-container smoke passed: native register, idempotent enroll, ingest, private default, QQ binding, field grants, revision conflict, revoke, logout.')


if __name__ == '__main__':
    main()

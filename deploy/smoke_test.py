"""Exercise a disposable localhost instance created by init_config.py, never production."""
import json
import time
from pathlib import Path
import urllib.request
import urllib.error

BASE = 'http://127.0.0.1:8080'
COOKIE = ''


def request(path, body=None, token=None, expected=200):
    global COOKIE
    headers = {'Content-Type': 'application/json', 'X-SOM-CSRF': '1', 'Origin': 'https://status.example.com'}
    if COOKIE:
        headers['Cookie'] = COOKIE
    if token:
        headers['Authorization'] = 'Bearer '+token
    req = urllib.request.Request(BASE+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (path, response.status, expected)
        if response.headers.get('Set-Cookie'):
            COOKIE = response.headers['Set-Cookie'].split(';', 1)[0]
        return json.load(response)


def main():
    for attempt in range(60):
        try:
            if request('/health')['ok']:
                break
        except (OSError, AssertionError):
            if attempt == 59:
                raise
            time.sleep(1)
    config = json.loads(Path('deploy/config.json').read_text())
    assert config['devices'] == {} and config['readers'] == {}
    request('/v1/accounts/register', {'name': 'smoke-user', 'password': 'disposable-test-password'})
    assert not request('/v1/accounts/me')['public']
    p = request('/v1/pairings', {'label': 'Disposable smoke device', 'platform': 'android'})
    request('/v1/pairings/approve', {'code': p['code'], 'account': 'smoke-user'})
    device = request('/v1/pairings/poll', {'code': p['code'], 'secret': p['secret']})
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
    assert request('/v1/bot/status/111111111?group_id='+group, token=token)['devices'][0]['app'] == 'example-editor'
    request('/v1/accounts/devices/revoke', {'id': device['device']})
    request('/v1/events', {'seq': 2, 'observed_at': time.time(), 'app': 'example-editor'}, device['token'], expected=401)
    print('Clean-container smoke passed: register, pair, ingest, private default, confirmed QQ binding, revoke.')


if __name__ == '__main__':
    main()

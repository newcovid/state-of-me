"""Tenant accounts, short-lived pairing and confirmed QQ bindings.
"""
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from threading import BoundedSemaphore
from fastapi import HTTPException, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, ConfigDict

BASE_FIELDS = ['app', 'app_details', 'music', 'lyric', 'lyric_window', 'device_state', 'presence', 'manual_status']
ALL_FIELDS = BASE_FIELDS + ['network', 'audio', 'resources', 'display', 'usage_summary', 'collector_health']
PASSWORD_WORK = BoundedSemaphore(2)
SCHEMA = '''
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL, salt TEXT NOT NULL, password TEXT NOT NULL, recovery TEXT NOT NULL, public_fields TEXT NOT NULL DEFAULT '[]');
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, uid TEXT NOT NULL, expires REAL NOT NULL);
CREATE TABLE IF NOT EXISTS user_devices(id TEXT PRIMARY KEY, uid TEXT NOT NULL, label TEXT NOT NULL, token_hash TEXT NOT NULL, platform TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS pairings(code TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, label TEXT NOT NULL, platform TEXT NOT NULL, expires REAL NOT NULL, uid TEXT, device TEXT);
CREATE TABLE IF NOT EXISTS qq_bindings(qq TEXT PRIMARY KEY, uid TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS group_grants(uid TEXT NOT NULL, group_id TEXT NOT NULL, fields TEXT NOT NULL, PRIMARY KEY(uid, group_id));
CREATE TABLE IF NOT EXISTS bind_codes(code_hash TEXT PRIMARY KEY, uid TEXT NOT NULL, group_id TEXT NOT NULL, expires REAL NOT NULL, candidate TEXT);
CREATE TABLE IF NOT EXISTS auth_limits(bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires REAL NOT NULL);
'''
_db = _config = _project = _authenticate = None

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

@contextmanager
def connection():
    with _db() as conn:
        conn.executescript(SCHEMA)
        yield conn

def raw_config():
    return _config()

def limit(request, kind, count=20, seconds=300):
    key = digest(kind + ':' + (request.client.host if request.client else 'unknown'))
    now = time.time()
    with connection() as c:
        c.execute('BEGIN IMMEDIATE')
        c.execute('DELETE FROM auth_limits WHERE expires < ?', (now,))
        row = c.execute('SELECT count FROM auth_limits WHERE bucket=?', (key,)).fetchone()
        if row and row[0] >= count:
            raise HTTPException(429, '尝试过于频繁，请稍后再试')
        c.execute('INSERT INTO auth_limits VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1', (key, now+seconds))

def password_hash(password, salt):
    if not PASSWORD_WORK.acquire(blocking=False):
        raise HTTPException(429, '服务正在处理登录，请稍后重试')
    try:
        return hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), 600000).hex()
    finally:
        PASSWORD_WORK.release()

def csrf(request):
    if request.headers.get('x-som-csrf') != '1':
        raise HTTPException(403, '请从账号页面发起操作')
    origin = request.headers.get('origin')
    expected = raw_config().get('accounts', {}).get('origin')
    if origin and expected and origin != expected:
        raise HTTPException(403, '来源不受信任')

def user(request, mutation=False):
    if mutation:
        csrf(request)
    token = request.cookies.get('som_session', '')
    with connection() as c:
        row = c.execute('SELECT users.id,users.name FROM sessions JOIN users ON users.id=sessions.uid WHERE token_hash=? AND expires>?', (digest(token), time.time())).fetchone()
    if not row:
        raise HTTPException(401, '请先登录账号')
    if mutation and request.headers.get('x-som-account') and request.headers['x-som-account'] != row[1]:
        raise HTTPException(409, '账号已变化，请重新核对后操作')
    return row

def session(response, uid):
    token = secrets.token_urlsafe(32)
    with connection() as c:
        c.execute('DELETE FROM sessions WHERE expires<?', (time.time(),))
        c.execute('INSERT INTO sessions VALUES(?,?,?)', (digest(token), uid, time.time()+30*86400))
    response.set_cookie('som_session', token, max_age=30*86400, httponly=True, secure=True, samesite='strict', path='/')

def audience(uid, fields=None):
    with connection() as c:
        ids = [x[0] for x in c.execute('SELECT id FROM user_devices WHERE uid=?', (uid,))]
    result = {'devices': ids, 'fields': ALL_FIELDS if fields is None else fields}
    if fields is not None:
        result['device_state_fields'] = ['battery_percent', 'charging', 'locked']
        result['app_details_fields'] = ['label']
        result['presence_fields'] = ['state']
    return result

def enrich(cfg):
    cfg['devices'] = {}
    with connection() as c:
        for identifier, uid, label, token, platform in c.execute('SELECT * FROM user_devices'):
            if token:
                cfg['devices'][identifier] = dict(token_hash=token, label=label, platform=platform, apps_mode='blacklist', apps_blocked=[], players_mode='blacklist', players_blocked=[], lyrics=True, **{f: True for f in ALL_FIELDS if f not in ('app','music','lyric','lyric_window')})
    return cfg

class Credentials(BaseModel):
    name: str = Field(pattern=r'^[a-zA-Z0-9_-]{3,32}$')
    password: str = Field(min_length=10, max_length=128)

class Recovery(Credentials):
    recovery: str = Field(min_length=24, max_length=128)

class PairRequest(BaseModel):
    label: str = Field(min_length=1, max_length=60)
    platform: str = Field(pattern=r'^(android|windows)$')

class PairPoll(BaseModel):
    code: str = Field(min_length=8, max_length=8)
    secret: str = Field(min_length=24, max_length=128)

class Code(BaseModel):
    code: str = Field(min_length=8, max_length=8)

class Approval(Code):
    account: str = Field(min_length=3, max_length=32)

class GrantRequest(BaseModel):
    group_id: str = Field(pattern=r'^\d{5,12}$')
    consent: bool = False

class BindClaim(Code):
    group_id: str = Field(pattern=r'^\d{5,12}$')
    qq: str = Field(pattern=r'^\d{5,12}$')

class PublicScope(BaseModel):
    enabled: bool

class Revoke(BaseModel):
    id: str = Field(max_length=100)


def short_code():
    return ''.join(secrets.choice('ABCDEFGHJKLMNPQRSTUVWXYZ23456789') for _ in range(8))

def bot_authorize(request, group_id):
    cfg = raw_config()
    group = cfg.get('accounts', {}).get('bot_groups', {}).get(group_id)
    if not group or not group.get('token'):
        raise HTTPException(403, '本群尚未接入多用户状态服务')
    _authenticate(request.headers.get('authorization'), {group_id: {'token_hash': digest(group['token'])}})
    return cfg, group


def install(app, db, config, project, authenticate, web):
    global _db, _config, _project, _authenticate
    _db, _config, _project, _authenticate = db, config, project, authenticate

    @app.get('/account')
    def page():
        return FileResponse(web/'account.html')

    @app.get('/account.js')
    def script():
        return FileResponse(web/'account.js', media_type='text/javascript')

    @app.get('/v1/accounts/info')
    def info():
        options = raw_config().get('accounts', {})
        return {'registration': options.get('registration', False), 'groups': list(options.get('bot_groups', {}))}

    @app.post('/v1/accounts/register')
    def register(body: Credentials, request: Request, response: Response):
        csrf(request); limit(request, 'register', 6)
        options = raw_config().get('accounts', {})
        if not options.get('registration', False):
            raise HTTPException(403, '此服务暂未开放注册')
        uid, salt, recovery = secrets.token_hex(16), secrets.token_hex(16), secrets.token_urlsafe(32)
        hashed = password_hash(body.password, salt)
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            if c.execute('SELECT COUNT(*) FROM users').fetchone()[0] >= options.get('max_users', 100):
                raise HTTPException(403, '账号名额已满，请联系服务维护者')
            try:
                c.execute('INSERT INTO users(id,name,salt,password,recovery) VALUES(?,?,?,?,?)', (uid, body.name.lower(), salt, hashed, digest(recovery)))
            except sqlite3.IntegrityError:
                raise HTTPException(409, '这个账号名已被使用')
        session(response, uid)
        return {'name': body.name.lower(), 'recovery': recovery}

    @app.post('/v1/accounts/login')
    def login(body: Credentials, request: Request, response: Response):
        csrf(request); limit(request, 'login', 20)
        with connection() as c:
            row = c.execute('SELECT id,salt,password FROM users WHERE name=?', (body.name.lower(),)).fetchone()
        calculated = password_hash(body.password, row[1] if row else '00'*16)
        if not row or not hmac.compare_digest(calculated, row[2]):
            raise HTTPException(401, '账号或密码不正确')
        session(response, row[0]); return {'ok': True}

    @app.post('/v1/accounts/recover')
    def recover(body: Recovery, request: Request):
        csrf(request); limit(request, 'recover', 6)
        salt, replacement = secrets.token_hex(16), secrets.token_urlsafe(32)
        hashed = password_hash(body.password, salt)
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT id,recovery FROM users WHERE name=?', (body.name.lower(),)).fetchone()
            if not row or not hmac.compare_digest(row[1], digest(body.recovery)):
                raise HTTPException(401, '账号或恢复码不正确')
            uid = row[0]
            c.execute('UPDATE users SET salt=?,password=?,recovery=? WHERE id=?', (salt, hashed, digest(replacement), uid))
            c.execute('DELETE FROM sessions WHERE uid=?', (uid,))
            for device, in c.execute('SELECT id FROM user_devices WHERE uid=?', (uid,)).fetchall():
                c.execute('DELETE FROM snapshots WHERE device=?', (device,))
            c.execute('DELETE FROM user_devices WHERE uid=?', (uid,))
            c.execute('DELETE FROM pairings WHERE uid=?', (uid,))
            c.execute('DELETE FROM bind_codes WHERE uid=?', (uid,))
        return {'recovery': replacement, 'detail': '密码已重设，旧登录和设备已撤销，请重新登录并配对'}

    @app.post('/v1/accounts/logout')
    def logout(request: Request, response: Response):
        user(request, True)
        with connection() as c:
            c.execute('DELETE FROM sessions WHERE token_hash=?', (digest(request.cookies.get('som_session', '')),))
        response.delete_cookie('som_session', path='/'); return {'ok': True}

    @app.get('/v1/accounts/me')
    def me(request: Request):
        uid, name = user(request)
        with connection() as c:
            devices = [dict(id=r[0], label=r[1], platform=r[2], phase='waiting' if not r[3] else 'synced' if r[4] else 'paired') for r in c.execute('SELECT id,label,platform,token_hash,EXISTS(SELECT 1 FROM snapshots WHERE device=user_devices.id) FROM user_devices WHERE uid=?', (uid,))]
            bindings = [r[0] for r in c.execute('SELECT qq FROM qq_bindings WHERE uid=?', (uid,))]
            groups = [r[0] for r in c.execute('SELECT group_id FROM group_grants WHERE uid=?', (uid,))]
            pending = [dict(id=r[0], group_id=r[1], qq=r[2]) for r in c.execute('SELECT code_hash,group_id,candidate FROM bind_codes WHERE uid=? AND expires>? AND candidate IS NOT NULL', (uid, time.time()))]
            public = json.loads(c.execute('SELECT public_fields FROM users WHERE id=?', (uid,)).fetchone()[0])
        return dict(name=name, devices=devices, qq=bindings, groups=groups, pending=pending, public=bool(public))

    @app.get('/v1/accounts/status')
    def own_status(request: Request):
        uid, _ = user(request)
        return project(enrich(raw_config()), audience(uid))

    @app.post('/v1/accounts/devices/revoke')
    def revoke_device(body: Revoke, request: Request):
        uid, _ = user(request, True)
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            if not c.execute('SELECT 1 FROM user_devices WHERE id=? AND uid=?', (body.id, uid)).fetchone():
                raise HTTPException(404, '没有找到此设备')
            c.execute('DELETE FROM snapshots WHERE device=?', (body.id,))
            c.execute('DELETE FROM user_devices WHERE id=?', (body.id,))
            c.execute('DELETE FROM pairings WHERE device=?', (body.id,))
        return {'ok': True}

    @app.post('/v1/device/disconnect')
    def disconnect(request: Request):
        identifier, _ = static_auth(request.headers.get('authorization'), enrich(raw_config())['devices'])
        with connection() as c:
            c.execute('DELETE FROM snapshots WHERE device=?', (identifier,))
            c.execute('DELETE FROM user_devices WHERE id=?', (identifier,))
            c.execute('DELETE FROM pairings WHERE device=?', (identifier,))
        return {'ok': True}

    @app.post('/v1/pairings')
    def pair(body: PairRequest, request: Request):
        limit(request, 'pair', 20)
        code, secret = short_code(), secrets.token_urlsafe(32)
        with connection() as c:
            c.execute("DELETE FROM user_devices WHERE token_hash='' AND id IN (SELECT device FROM pairings WHERE expires<?)", (time.time(),))
            c.execute('DELETE FROM pairings WHERE expires<?', (time.time(),))
            c.execute('INSERT INTO pairings(code,secret_hash,label,platform,expires) VALUES(?,?,?,?,?)', (code, digest(secret), body.label, body.platform, time.time()+600))
        return {'code': code, 'secret': secret, 'expires_in': 600}

    @app.get('/v1/accounts/pairings/{code}')
    def pairing_details(code: str, request: Request):
        user(request); limit(request, 'pair-details', 40)
        with connection() as c:
            row = c.execute('SELECT label,platform,uid FROM pairings WHERE code=? AND expires>?', (code.upper(), time.time())).fetchone()
        if not row or row[2]:
            raise HTTPException(404, '配对码错误、过期或已使用')
        return {'label': row[0], 'platform': row[1]}

    @app.post('/v1/pairings/approve')
    def approve(body: Approval, request: Request):
        uid, name = user(request, True); limit(request, 'approve', 20)
        if body.account != name:
            raise HTTPException(409, '账号已变化，请重新核对设备')
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT label,platform,uid FROM pairings WHERE code=? AND expires>?', (body.code.upper(), time.time())).fetchone()
            if not row:
                raise HTTPException(404, '配对码错误或已过期，请在客户端重新生成')
            if row[2]:
                raise HTTPException(409, '配对码已经确认，不能重复使用')
            if c.execute('SELECT COUNT(*) FROM user_devices WHERE uid=?', (uid,)).fetchone()[0] >= 10:
                raise HTTPException(409, '最多绑定 10 台设备，请先移除旧设备')
            device = 'd_'+secrets.token_hex(16)
            c.execute('INSERT INTO user_devices VALUES(?,?,?,?,?)', (device, uid, row[0], '', row[1]))
            c.execute('UPDATE pairings SET uid=?,device=? WHERE code=?', (uid, device, body.code.upper()))
        return {'name': name, 'device_label': row[0]}

    @app.post('/v1/pairings/poll')
    def poll(body: PairPoll):
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT secret_hash,uid,device,label FROM pairings WHERE code=? AND expires>?', (body.code.upper(), time.time())).fetchone()
            if not row or not hmac.compare_digest(row[0], digest(body.secret)):
                raise HTTPException(404, '配对已失效，请重新开始')
            if not row[1]:
                return {'status': 'pending'}
            token = hmac.new(body.secret.encode(), b'state-of-me device', hashlib.sha256).hexdigest()
            c.execute('UPDATE user_devices SET token_hash=? WHERE id=?', (digest(token), row[2]))
            name = c.execute('SELECT name FROM users WHERE id=?', (row[1],)).fetchone()[0]
        return {'status': 'approved', 'token': token, 'account': name, 'device': row[2], 'label': row[3]}

    @app.post('/v1/accounts/bind-code')
    def bind_code(body: GrantRequest, request: Request):
        uid, _ = user(request, True); limit(request, 'bind-code', 10)
        if not body.consent:
            raise HTTPException(400, '请先确认允许本群查询基本状态')
        if body.group_id not in raw_config().get('accounts', {}).get('bot_groups', {}):
            raise HTTPException(400, '此群尚未接入')
        code = short_code()
        with connection() as c:
            c.execute('DELETE FROM bind_codes WHERE expires<? OR uid=?', (time.time(), uid))
            c.execute('INSERT INTO bind_codes VALUES(?,?,?,?,NULL)', (digest(code), uid, body.group_id, time.time()+600))
        return {'code': code, 'expires_in': 600}

    @app.post('/v1/bot/bind')
    def claim(body: BindClaim, request: Request):
        bot_authorize(request, body.group_id); limit(request, 'bot-bind', 60)
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT candidate,group_id FROM bind_codes WHERE code_hash=? AND expires>?', (digest(body.code.upper()), time.time())).fetchone()
            if not row or row[1] != body.group_id:
                raise HTTPException(404, '绑定码错误、过期或不属于此群')
            if row[0] and row[0] != body.qq:
                raise HTTPException(409, '此码已提交，请重新生成')
            c.execute('UPDATE bind_codes SET candidate=? WHERE code_hash=?', (body.qq, digest(body.code.upper())))
        return {'detail': '身份已提交。请回到账号页面核对 QQ 与群号，点击确认后才会绑定。'}

    @app.post('/v1/accounts/bind-confirm')
    def confirm(body: Revoke, request: Request):
        uid, _ = user(request, True)
        with connection() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT group_id,candidate FROM bind_codes WHERE code_hash=? AND uid=? AND expires>?', (body.id, uid, time.time())).fetchone()
            if not row or not row[1]:
                raise HTTPException(404, '没有待确认的身份，请重新生成绑定码')
            try:
                existing = c.execute('SELECT qq FROM qq_bindings WHERE uid=?', (uid,)).fetchone()
                if existing and existing[0] != row[1]:
                    raise HTTPException(409, '此账号已绑定其他 QQ，请先解除旧绑定')
                c.execute('INSERT INTO qq_bindings VALUES(?,?) ON CONFLICT(qq) DO NOTHING', (row[1], uid))
                if c.execute('SELECT uid FROM qq_bindings WHERE qq=?', (row[1],)).fetchone()[0] != uid:
                    raise HTTPException(409, '此 QQ 已绑定其他账号')
                c.execute('INSERT OR REPLACE INTO group_grants VALUES(?,?,?)', (uid, row[0], json.dumps(BASE_FIELDS)))
                c.execute('DELETE FROM bind_codes WHERE code_hash=?', (body.id,))
            except sqlite3.IntegrityError:
                raise HTTPException(409, '绑定冲突，请先解除旧绑定')
        return {'ok': True}

    @app.post('/v1/accounts/groups/revoke')
    def revoke_group(body: Revoke, request: Request):
        uid, _ = user(request, True)
        with connection() as c:
            c.execute('DELETE FROM group_grants WHERE uid=? AND group_id=?', (uid, body.id))
            c.execute('DELETE FROM bind_codes WHERE uid=? AND group_id=?', (uid, body.id))
        return {'ok': True}

    @app.post('/v1/accounts/qq/unbind')
    def unbind(request: Request):
        uid, _ = user(request, True)
        with connection() as c:
            c.execute('DELETE FROM qq_bindings WHERE uid=?', (uid,))
            c.execute('DELETE FROM group_grants WHERE uid=?', (uid,))
            c.execute('DELETE FROM bind_codes WHERE uid=?', (uid,))
        return {'ok': True}

    @app.get('/v1/bot/status/{qq}')
    def bot_status(qq: str, group_id: str, request: Request):
        cfg, group = bot_authorize(request, group_id)
        with connection() as c:
            row = c.execute('SELECT uid FROM qq_bindings WHERE qq=?', (qq,)).fetchone()
            if row:
                grant = c.execute('SELECT fields FROM group_grants WHERE uid=? AND group_id=?', (row[0], group_id)).fetchone()
                if not grant:
                    return {'status': 'not_shared', 'devices': []}
                scope = audience(row[0], json.loads(grant[0]))
            else:
                return {'status': 'unbound', 'devices': []}
        return project(enrich(cfg), scope)

    @app.post('/v1/accounts/public')
    def public_scope(body: PublicScope, request: Request):
        uid, _ = user(request, True)
        with connection() as c:
            c.execute('UPDATE users SET public_fields=? WHERE id=?', (json.dumps(BASE_FIELDS if body.enabled else []), uid))
        return {'ok': True}

    @app.get('/v1/public/users/{name}')
    def public_user(name: str):
        with connection() as c:
            row = c.execute('SELECT id,public_fields FROM users WHERE name=?', (name.lower(),)).fetchone()
        if not row or not json.loads(row[1]):
            raise HTTPException(404, '未开启公开分享')
        return project(enrich(raw_config()), audience(row[0], json.loads(row[1])))

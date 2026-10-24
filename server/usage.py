"""Historical daily aggregates: private by default, independent of live presence TTL."""
import json
import time
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from typing import Literal
from fastapi import Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator

class AppUsage(BaseModel):
    model_config = ConfigDict(extra='forbid')
    package: str = Field(min_length=1, max_length=200)
    label: str = Field(max_length=120)
    foreground_ms: int = Field(ge=0, le=90000000)
    sessions: int = Field(ge=0, le=1000000)

class DayUsage(BaseModel):
    model_config = ConfigDict(extra='forbid')
    date: date
    screen_ms: int = Field(ge=0, le=90000000)
    unlocked_ms: int = Field(ge=0, le=90000000)
    known_ms: int = Field(ge=0, le=90000000)
    unlocks: int = Field(ge=0, le=1000000)
    hourly_screen_ms: list[int] = Field(min_length=24, max_length=24)
    apps: list[AppUsage] = Field(max_length=50)
    apps_truncated: bool = False

    @model_validator(mode='after')
    def validate_intervals(self):
        if self.unlocked_ms > self.screen_ms or any(not 0 <= x <= 7200000 for x in self.hourly_screen_ms) or sum(self.hourly_screen_ms) != self.screen_ms:
            raise ValueError('Invalid duration totals')
        if len({a.package for a in self.apps}) != len(self.apps):
            raise ValueError('Duplicate application')
        return self

class Upload(BaseModel):
    model_config = ConfigDict(extra='forbid')
    clear: bool = False
    source: Literal['android_usage_events'] = 'android_usage_events'
    timezone: str = Field(default='UTC', max_length=80)
    sampled_at: int = Field(default=0, ge=0)
    first_event_at: int = Field(default=0, ge=0)
    days: list[DayUsage] = Field(default_factory=list, max_length=30)

    @model_validator(mode='after')
    def validate_dates(self):
        if self.clear:
            if self.days:
                raise ValueError('Clear must not include records')
            return self
        try:
            zone = ZoneInfo(self.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError('Unknown timezone')
        if abs(time.time() - self.sampled_at) > 1800 or self.first_event_at > self.sampled_at:
            raise ValueError('Stale or future sample')
        today = datetime.fromtimestamp(self.sampled_at, zone).date()
        if len({d.date for d in self.days}) != len(self.days) or any(not today-timedelta(days=29) <= d.date <= today for d in self.days):
            raise ValueError('Dates outside retention window or duplicated')
        return self

class GroupScope(BaseModel):
    group_id: str = Field(pattern=r'^\d{5,12}$')
    enabled: bool

def schema(c):
    c.execute('CREATE TABLE IF NOT EXISTS usage_history(device TEXT PRIMARY KEY, received REAL NOT NULL, payload TEXT NOT NULL)')
    c.execute('DELETE FROM usage_history WHERE received < ?', (time.time()-30*86400,))

def install(app, db, config, authenticate, accounts):
    def read(uid, days):
        with accounts.connection() as c:
            schema(c)
            rows = c.execute('SELECT d.id,d.label,h.received,h.payload FROM user_devices d JOIN usage_history h ON h.device=d.id WHERE d.uid=?', (uid,)).fetchall()
        result = []
        for identifier, label, received, payload in rows:
            data = json.loads(payload)
            zone = ZoneInfo(data['timezone'])
            cutoff = datetime.now(zone).date()-timedelta(days=days-1)
            data['days'] = [d for d in data['days'] if date.fromisoformat(d['date']) >= cutoff]
            if data['days']:
                result.append(dict(device=identifier, device_label=label, received_at=received, **data))
        return dict(status='available' if result else 'unavailable', devices=result, server_time=time.time(), requested_days=days)

    @app.post('/v1/usage')
    def upload(body: Upload, authorization: str | None = Header(default=None)):
        device, rule = authenticate(authorization, config()['devices'])
        if rule.get('platform') != 'android':
            raise HTTPException(403, 'Android usage source required')
        with db() as c:
            c.execute('BEGIN IMMEDIATE')
            schema(c)
            if body.clear:
                c.execute('DELETE FROM usage_history WHERE device=?', (device,))
            else:
                old = c.execute('SELECT payload FROM usage_history WHERE device=?', (device,)).fetchone()
                if old and json.loads(old[0])['sampled_at'] > body.sampled_at:
                    raise HTTPException(409, 'Older summary cannot replace newer data')
                c.execute('INSERT OR REPLACE INTO usage_history VALUES(?,?,?)', (device, time.time(), body.model_dump_json(exclude={'clear'})))
        return {'ok': True}

    @app.get('/v1/accounts/usage')
    def own(request: Request, days: int = 7):
        uid, _ = accounts.user(request)
        if days not in (1, 7, 30):
            raise HTTPException(422, 'days must be 1, 7 or 30')
        return read(uid, days)

    @app.post('/v1/accounts/groups/usage')
    def scope(body: GroupScope, request: Request):
        uid, _ = accounts.user(request, True)
        with accounts.connection() as c:
            row = c.execute('SELECT fields FROM group_grants WHERE uid=? AND group_id=?', (uid, body.group_id)).fetchone()
            if not row:
                raise HTTPException(404, '请先绑定并授权此群')
            fields = set(json.loads(row[0])); fields.discard('usage_history')
            if body.enabled:
                fields.add('usage_history')
            c.execute('UPDATE group_grants SET fields=? WHERE uid=? AND group_id=?', (json.dumps(sorted(fields)), uid, body.group_id))
        return {'ok': True}

    @app.get('/v1/bot/usage/{qq}')
    def bot(qq: str, group_id: str, request: Request, days: int = 7):
        accounts.bot_authorize(request, group_id)
        if days not in (1, 7, 30):
            raise HTTPException(422, 'days must be 1, 7 or 30')
        with accounts.connection() as c:
            row = c.execute('SELECT uid FROM qq_bindings WHERE qq=?', (qq,)).fetchone()
            if not row:
                return {'status': 'unbound', 'devices': []}
            grant = c.execute('SELECT fields FROM group_grants WHERE uid=? AND group_id=?', (row[0], group_id)).fetchone()
            if not grant or 'usage_history' not in json.loads(grant[0]):
                return {'status': 'not_shared', 'devices': []}
        result = read(row[0], days)
        # Keep the agent payload bounded: full daily totals plus one ranking per device.
        for device in result['devices']:
            ranking = {}
            for day in device['days']:
                for item in day.pop('apps'):
                    total = ranking.setdefault(item['package'], dict(package=item['package'], label=item['label'], foreground_ms=0, sessions=0))
                    total['foreground_ms'] += item['foreground_ms']; total['sessions'] += item['sessions']
                day.pop('hourly_screen_ms', None)
            device['app_ranking'] = sorted(ranking.values(), key=lambda x: x['foreground_ms'], reverse=True)[:50]
            device['ranking_limit'] = 50
        return result

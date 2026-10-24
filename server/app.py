"""Presence service with isolated accounts and backwards-compatible device credentials."""
import hashlib
import hmac
import json
import os
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

app = FastAPI(title="State of Me", version="0.12.0")
DB = os.getenv("SOM_DB", "state.db")
CONFIG = os.getenv("SOM_CONFIG", "config.json")
WEB = Path(__file__).parent / "web"


class LyricLine(BaseModel):
    model_config = ConfigDict(extra="forbid")
    at_ms: int = Field(ge=0, le=604800000)
    text: str = Field(max_length=200)


class LyricWindow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    start_ms: int = Field(ge=0, le=604800000)
    end_ms: int = Field(ge=0, le=604800001)
    lines: list[LyricLine] = Field(max_length=16)

    @model_validator(mode='after')
    def ordered(self):
        if self.end_ms <= self.start_ms or any(
            not self.start_ms <= x.at_ms < self.end_ms for x in self.lines
        ) or any(a.at_ms >= b.at_ms for a, b in zip(self.lines, self.lines[1:])):
            raise ValueError('Window must contain strictly ordered timestamps within bounds')
        return self


class Music(BaseModel):
    model_config = ConfigDict(extra="forbid")
    media_kind: Literal["music", "video", "unknown"] = "music"
    position_known: bool = True
    title: str = Field(max_length=200)
    artist: str = Field(default="", max_length=200)
    album: str = Field(default="", max_length=200)
    player: str = Field(default="", max_length=200)
    playback_status: Literal["unknown", "playing", "paused", "buffering", "connecting", "stopped", "error"] = "unknown"
    playing: bool = False
    position_ms: int = Field(default=0, ge=0, le=604800000)
    duration_ms: int = Field(default=0, ge=0, le=604800000)
    playback_rate: float = Field(default=1, ge=0, le=8, allow_inf_nan=False)
    track_id: str = Field(default='', max_length=128)
    lyric_window: LyricWindow | None = None
    lyric: str | None = Field(default=None, max_length=500)
    lyric_status: Literal['unavailable', 'synced', 'plain', 'ambiguous', 'unmatched', 'permission_required', 'unsupported'] = 'unavailable'


class Presence(BaseModel):
    model_config = ConfigDict(extra='forbid')
    state: Literal['active', 'idle', 'locked', 'unknown'] = 'unknown'
    idle_seconds: int | None = Field(default=None, ge=0, le=4294968)


class ManualStatus(BaseModel):
    model_config = ConfigDict(extra='forbid')
    text: str = Field(max_length=120)
    expires_at: int = Field(ge=0)


class Display(BaseModel):
    model_config = ConfigDict(extra='forbid')
    monitor_count: int | None = Field(default=None, ge=0, le=128)
    remote_session: bool | None = None
    fullscreen: bool | None = None


class UsageSummary(BaseModel):
    model_config = ConfigDict(extra='forbid')
    source: Literal['whatpulse'] = 'whatpulse'
    scope: Literal['since_pulse'] = 'since_pulse'
    sampled_at: int = Field(ge=0)
    keys: int | None = Field(default=None, ge=0, le=9007199254740991)
    clicks: int | None = Field(default=None, ge=0, le=9007199254740991)
    scrolls: int | None = Field(default=None, ge=0, le=9007199254740991)
    download: int | None = Field(default=None, ge=0, le=9007199254740991)
    upload: int | None = Field(default=None, ge=0, le=9007199254740991)
    uptime: int | None = Field(default=None, ge=0, le=9007199254740991)
    keys_per_second: float | None = Field(default=None, ge=0, le=1000000, allow_inf_nan=False)
    clicks_per_second: float | None = Field(default=None, ge=0, le=1000000, allow_inf_nan=False)
    download_bytes_per_second: float | None = Field(default=None, ge=0, le=1e15, allow_inf_nan=False)
    upload_bytes_per_second: float | None = Field(default=None, ge=0, le=1e15, allow_inf_nan=False)
    sample_seconds: float | None = Field(default=None, ge=0, le=120, allow_inf_nan=False)


class DeviceState(BaseModel):
    model_config = ConfigDict(extra="forbid")
    battery_present: bool | None = None
    ac_connected: bool | None = None
    battery_percent: int | None = Field(default=None, ge=0, le=100)
    charging: bool | None = None
    screen_on: bool | None = None
    locked: bool | None = None
    power_save: bool | None = None
    battery_temperature_c: float | None = Field(default=None, ge=-50, le=100, allow_inf_nan=False)
    battery_voltage_mv: int | None = Field(default=None, ge=0, le=20000)
    charge_source: Literal['none', 'ac', 'usb', 'wireless', 'dock', 'unknown'] | None = None
    battery_health: Literal['good', 'overheat', 'dead', 'over_voltage', 'failure', 'cold', 'unknown'] | None = None


class Network(BaseModel):
    model_config = ConfigDict(extra="forbid")
    connected: bool | None = None
    validated: bool | None = None
    metered: bool | None = None
    transports: list[Literal['wifi', 'cellular', 'ethernet', 'vpn', 'bluetooth', 'other']] = Field(default_factory=list, max_length=6)


class Audio(BaseModel):
    default_output_form: Literal["speaker", "headphones", "headset", "line", "digital", "other"] | None = None
    active_output_sessions: int | None = Field(default=None, ge=0, le=4096)
    model_config = ConfigDict(extra="forbid")
    music_volume_percent: int | None = Field(default=None, ge=0, le=100)
    media_muted: bool | None = None
    ringer_mode: Literal['silent', 'vibrate', 'normal', 'unknown'] | None = None
    interruption_filter: Literal['all', 'priority', 'none', 'alarms', 'unknown'] | None = None
    connected_output_types: list[Literal['speaker', 'wired', 'bluetooth', 'usb', 'hdmi', 'other']] = Field(default_factory=list, max_length=6)


class Resources(BaseModel):
    model_config = ConfigDict(extra="forbid")
    available_memory_mib: int | None = Field(default=None, ge=0, le=1073741824)
    total_memory_mib: int | None = Field(default=None, ge=0, le=1073741824)
    low_memory: bool | None = None
    available_storage_mib: int | None = Field(default=None, ge=0, le=1073741824)
    total_storage_mib: int | None = Field(default=None, ge=0, le=1073741824)
    cpu_percent: float | None = Field(default=None, ge=0, le=100, allow_inf_nan=False)
    thermal_status: int | None = Field(default=None, ge=0, le=6)


class CollectorHealth(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: str = Field(default='', max_length=32)
    sdk: int | None = Field(default=None, ge=26, le=1000)
    notification_access: bool | None = None
    usage_access: bool | None = None
    music_directory_access: bool | None = None
    battery_optimization_exempt: bool | None = None


class AppDetails(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(default='', max_length=120)
    active_for_ms: int | None = Field(default=None, ge=0, le=3600000)


CONTEXT_FIELDS = ('device_state', 'network', 'audio', 'resources', 'collector_health', 'app_details', 'presence', 'manual_status', 'display', 'usage_summary')


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid")
    seq: int = Field(ge=0)
    observed_at: float = Field(allow_inf_nan=False)
    hidden: bool = False
    app: str | None = Field(default=None, max_length=120)
    music: Music | None = None
    device_state: DeviceState | None = None
    network: Network | None = None
    audio: Audio | None = None
    resources: Resources | None = None
    collector_health: CollectorHealth | None = None
    app_details: AppDetails | None = None
    presence: Presence | None = None
    manual_status: ManualStatus | None = None
    display: Display | None = None
    usage_summary: UsageSummary | None = None


def active_music(music):
    # Legacy clients omit playback_status; an explicit non-playing state wins.
    return bool(music and music.get('playing') is True and
                music.get('playback_status', 'unknown') in ('playing', 'unknown'))


def permitted(rule, category, package):
    if not package:
        return False
    mode = rule.get(category + '_mode', 'whitelist')
    if mode == 'blacklist':
        return package not in rule.get(category + '_blocked', [])
    return mode == 'whitelist' and package in rule.get(category, [])


def raw_config():
    return json.loads(Path(CONFIG).read_text(encoding="utf-8"))


def config():
    return accounts.enrich(raw_config())


@contextmanager
def db():
    conn = sqlite3.connect(DB, timeout=5)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("CREATE TABLE IF NOT EXISTS snapshots (device TEXT PRIMARY KEY, seq INTEGER, received REAL, payload TEXT)")
    try:
        with conn:
            yield conn
    finally:
        conn.close()


def authenticate(authorization, entries):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Bearer credential required")
    token = authorization[7:]
    if len(token) < 24:
        raise HTTPException(401, "Invalid credential")
    for identifier, entry in entries.items():
        candidate = hashlib.sha256(token.encode()).hexdigest()
        expected = entry.get('token_hash', '')
        if entry.get('enabled', True) and hmac.compare_digest(candidate, expected):
            return identifier, entry
    raise HTTPException(401, "Invalid credential")


@app.middleware("http")
async def guard(request: Request, call_next):
    # Never cache personal state, even for unauthenticated public projections.
    length = request.headers.get("content-length")
    if length:
        try:
            if int(length) < 0 or int(length) > 16384:
                return JSONResponse({"detail": "Payload too large"}, status_code=413)
        except ValueError:
            return JSONResponse({"detail": "Invalid content length"}, status_code=400)
    if request.method == "POST":
        body = await request.body()
        if len(body) > 16384:
            return JSONResponse({"detail": "Payload too large"}, status_code=413)
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'self'"
    return response


@app.get("/health")
def health():
    with db() as conn:
        conn.execute("SELECT 1")
    return {"ok": True, "version": "0.12.0"}


@app.post("/v1/events")
def ingest(event: Event, authorization: str | None = Header(default=None)):
    cfg = config()
    device, rule = authenticate(authorization, cfg["devices"])
    now = time.time()
    if abs(now - event.observed_at) > 120:
        raise HTTPException(422, "Clock skew or stale event; synchronize device clock")
    value = event.model_dump()
    # Defense in depth: server allowlists also protect against a broken collector.
    if event.hidden:
        value.update(app=None, music=None, device_state=None)
    else:
        if not permitted(rule, "apps", event.app):
            value["app"] = None
        if event.music and not permitted(rule, "players", event.music.player):
            value["music"] = None
        elif value["music"] and not rule.get("lyrics", False):
            value["music"]["lyric"] = None
            value["music"]["lyric_status"] = 'unavailable'
        if value['music'] and not rule.get('lyrics', False):
            value['music']['lyric_window'] = None
    if not active_music(value.get('music')):
        value['music'] = None
    for field in CONTEXT_FIELDS:
        if event.hidden or not rule.get(field, False):
            value[field] = None
    if not value['app']:
        value['app_details'] = None
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        old = conn.execute("SELECT seq, received FROM snapshots WHERE device=?", (device,)).fetchone()
        if old and event.seq <= old[0]:
            raise HTTPException(409, "Out of order sequence")
        if old and now - old[1] < 1 and not event.hidden:
            raise HTTPException(429, "At most one update per second")
        conn.execute("INSERT OR REPLACE INTO snapshots VALUES (?,?,?,?)", (device, event.seq, now, json.dumps(value)))
    return {"accepted": True}


def project(cfg, audience):
    now = time.time()
    ttl = min(max(cfg.get("ttl_seconds", 45), 10), 120)
    with db() as conn:
        rows = conn.execute("SELECT device,received,payload FROM snapshots").fetchall()
    result = []
    for identifier, received, payload in rows:
        device = cfg["devices"].get(identifier, {})
        if not device.get("enabled", True) or identifier not in audience.get("devices", []):
            continue
        data = json.loads(payload)
        if now - received > ttl or data["hidden"]:
            continue
        # Apply current rules again: a config change immediately retracts old data.
        fields = audience.get("fields", [])
        item = {"device": identifier, "age_seconds": round(now - received), "expires_in": max(0, round(ttl - (now - received)))}
        if device.get("label"):
            item["device_label"] = device["label"]
        if "app" in fields and permitted(device, "apps", data["app"]):
            item["app"] = data["app"]
        music = data.get("music")
        if "music" in fields and active_music(music) and permitted(device, "players", music["player"]):
            music = dict(music)
            # Reception time anchors progress without depending on device wall clocks.
            position = music['position_ms'] + (max(0, now-received)*1000*music.get('playback_rate', 1) if music['playing'] and music.get('position_known', True) else 0)
            music['position_ms'] = int(min(position, music['duration_ms'] or 604800000))
            window = music.get('lyric_window')
            if window:
                music['lyric'] = None
                if window['start_ms'] <= music['position_ms'] < window['end_ms']:
                    for line in window['lines']:
                        if line['at_ms'] <= music['position_ms']:
                            music['lyric'] = line['text']
            if "lyric" not in fields or not device.get("lyrics", False):
                music["lyric"] = None
                music["lyric_status"] = 'unavailable'
            if 'lyric_window' not in fields or 'lyric' not in fields or not device.get('lyrics', False):
                music['lyric_window'] = None
            item["music"] = music
        for field in CONTEXT_FIELDS:
            if field in fields and device.get(field, False) and data.get(field):
                if field != 'app_details' or item.get('app'):
                    value = data[field]
                    if field == 'manual_status' and not now < value.get('expires_at', 0) <= now + 86400:
                        continue
                    if field == 'usage_summary' and not -5 <= now - value.get('sampled_at', 0) <= 45:
                        continue
                    # Audience subfield scopes prevent a broad category leaking diagnostics.
                    if field + '_fields' in audience:
                        value = {k: v for k, v in value.items() if k in audience[field + '_fields']}
                    if value:
                        item[field] = value
        if any(item.get(field) for field in ('app', 'music', *CONTEXT_FIELDS)):
            result.append(item)
    return {"status": "available" if result else "unavailable", "devices": result, "refresh_seconds": 5, "server_time": now}


@app.get("/")
def index():
    return FileResponse(WEB / "index.html")


@app.get("/ui.js")
def javascript():
    return FileResponse(WEB / "ui.js", media_type="text/javascript")


@app.get("/style.css")
def css():
    return FileResponse(WEB / "style.css", media_type="text/css")


@app.get('/playback.js')
def playback_javascript():
    return FileResponse(WEB / 'playback.js', media_type='text/javascript')


@app.get("/widget.js")
def widget_javascript():
    return FileResponse(WEB / "widget.js", media_type="text/javascript")


from . import accounts
accounts.install(app, db, raw_config, project, authenticate, WEB)

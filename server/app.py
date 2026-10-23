"""Single-owner presence service. Device writes and audience reads are separate."""
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

app = FastAPI(title="State of Me", version="0.6.0")
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
    title: str = Field(max_length=200)
    artist: str = Field(default="", max_length=200)
    album: str = Field(default="", max_length=200)
    player: str = Field(default="", max_length=200)
    playing: bool = False
    position_ms: int = Field(default=0, ge=0, le=604800000)
    duration_ms: int = Field(default=0, ge=0, le=604800000)
    playback_rate: float = Field(default=1, ge=0, le=8, allow_inf_nan=False)
    track_id: str = Field(default='', max_length=128)
    lyric_window: LyricWindow | None = None
    lyric: str | None = Field(default=None, max_length=500)
    lyric_status: Literal['unavailable', 'synced', 'plain', 'ambiguous', 'unmatched', 'permission_required', 'unsupported'] = 'unavailable'


class DeviceState(BaseModel):
    model_config = ConfigDict(extra="forbid")
    battery_percent: int | None = Field(default=None, ge=0, le=100)
    charging: bool | None = None
    screen_on: bool | None = None
    locked: bool | None = None
    power_save: bool | None = None


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid")
    seq: int = Field(ge=0)
    observed_at: float = Field(allow_inf_nan=False)
    hidden: bool = False
    app: str | None = Field(default=None, max_length=120)
    music: Music | None = None
    device_state: DeviceState | None = None


def config():
    return json.loads(Path(CONFIG).read_text(encoding="utf-8"))


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
        if entry.get("enabled", True) and hmac.compare_digest(token, entry["token"]):
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
    return {"ok": True, "version": "0.6.0"}


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
        if event.app not in rule.get("apps", []):
            value["app"] = None
        if event.music and event.music.player not in rule.get("players", []):
            value["music"] = None
        elif value["music"] and not rule.get("lyrics", False):
            value["music"]["lyric"] = None
            value["music"]["lyric_status"] = 'unavailable'
        if value['music'] and not rule.get('lyrics', False):
            value['music']['lyric_window'] = None
    if not rule.get("device_state", False):
        value["device_state"] = None
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
        if "app" in fields and data["app"] in device.get("apps", []):
            item["app"] = data["app"]
        music = data.get("music")
        if "music" in fields and music and music["player"] in device.get("players", []):
            music = dict(music)
            # Reception time anchors progress without depending on device wall clocks.
            position = music['position_ms'] + (max(0, now-received)*1000*music.get('playback_rate', 1) if music['playing'] else 0)
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
        if 'device_state' in fields and device.get('device_state', False) and data.get('device_state'):
            item['device_state'] = data['device_state']
        if item.get("app") or item.get("music") or item.get('device_state'):
            result.append(item)
    return {"status": "available" if result else "unavailable", "devices": result, "refresh_seconds": 5, "server_time": now}


@app.get("/v1/status")
def status(authorization: str | None = Header(default=None)):
    cfg = config()
    _, audience = authenticate(authorization, cfg["readers"])
    return project(cfg, audience)


@app.get("/v1/public/status")
def public_status():
    cfg = config()
    audience = cfg.get("public", {})
    if not audience.get("enabled", False):
        raise HTTPException(404, "Public sharing disabled")
    return project(cfg, audience)


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

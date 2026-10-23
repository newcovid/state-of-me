"""Single-owner presence service. Device writes and audience reads are separate."""
import hashlib
import hmac
import json
import os
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field

app = FastAPI(title="State of Me", version="0.1.0")
DB = os.getenv("SOM_DB", "state.db")
CONFIG = os.getenv("SOM_CONFIG", "config.json")
WEB = Path(__file__).parent / "web"


class Music(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(max_length=200)
    artist: str = Field(default="", max_length=200)
    player: str = Field(default="", max_length=200)
    playing: bool = False
    position_ms: int = Field(default=0, ge=0, le=604800000)
    duration_ms: int = Field(default=0, ge=0, le=604800000)
    lyric: str | None = Field(default=None, max_length=500)


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid")
    seq: int = Field(ge=0)
    observed_at: float = Field(allow_inf_nan=False)
    hidden: bool = False
    app: str | None = Field(default=None, max_length=120)
    music: Music | None = None


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
    return {"ok": True, "version": "0.1.0"}


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
        value.update(app=None, music=None)
    else:
        if event.app not in rule.get("apps", []):
            value["app"] = None
        if event.music and event.music.player not in rule.get("players", []):
            value["music"] = None
        elif value["music"] and not rule.get("lyrics", False):
            value["music"]["lyric"] = None
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
            # Never extrapolate stale lyrics; collector sends only the current line.
            if "lyric" not in fields or not device.get("lyrics", False):
                music["lyric"] = None
            item["music"] = music
        if item.get("app") or item.get("music"):
            result.append(item)
    return {"status": "available" if result else "unavailable", "devices": result, "refresh_seconds": 5}


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

# Android 0.9.1 synchronization

The collector sends complete snapshots through the existing `/v1/events` endpoint. The server keeps its 45-second expiry. Existing readers, AstrBot, and the Halo widget keep using the same protocol.

## Scheduling

- Media playback/metadata callbacks, session-list changes, screen/lock events, battery level/charging changes, sharing preferences and network reconnection trigger collection. Events coalesce behind the existing 1.1-second completion-based throttle.
- Unchanged snapshots are suppressed until a 20-second heartbeat. Natural playback progress, current lyric, lyric-window movement, foreground duration, battery voltage/temperature and available RAM/storage do not independently trigger transmission.
- A seek greater than 1.5 seconds relative to the last sent playback anchor triggers resynchronization. All permitted media controllers are watched so resuming another existing session is detected.
- Lyrics are parsed once per source. The bounded window is refreshed before its end, accounting for playback speed and the actual payload-trimmed window. Dense lyrics can require more frequent uploads than the heartbeat.
- Foreground app detection remains a local five-second poll when app sharing is configured and the screen is unlocked. The initial query covers up to one hour; subsequent queries use an incremental cursor with a one-second overlap. Disabled app sharing skips usage queries.
- Optional context categories are cached for up to 20 seconds. Preference/network changes invalidate the cache. Application labels are cached for the current package.
- Failed attempts back off through 5, 10, 20, 40, 80, 160 and 300 seconds. Offline attempts skip collection and HTTP. Reconnection requests a fresh snapshot. Explicit pause makes one best-effort hidden upload even during backoff; offline old state expires normally.
- Sequence numbers are durably reserved only for actual upload attempts. Response streams are consumed/closed and connections are cleaned up on both success and exceptions.

Timers do not hold a wake lock. Doze/OEM restrictions can delay execution; neither the heartbeat nor callbacks guarantee uninterrupted background delivery. Custom deployments should keep TTL comfortably above the 20-second heartbeat plus network latency; a 10-second TTL is incompatible with this schedule.

## Verification

`PublishPolicyTest` covers suppression, heartbeat deadlines, seek/pause updates, lyric refill, retry and reconnect. Its deterministic unchanged-hour scenario produces 180 requests, compared with 720 for an unconditional five-second sender. This is a scheduling comparison, not a measured battery saving.

The backend contract test verifies progress/lyrics after a 20-second gap, immediate music removal on a nonmusic snapshot, and expiry after 45 seconds. Android release assembly and release lint are required alongside Java, Python and browser contract tests.

The service exposes process-lifetime counters without activity names, lyrics or credentials:

```sh
adb shell dumpsys activity service io.github.stateofme/.Collector
```

Counters: `collections`, HTTP attempts `uploads`, deduplicated `skipped`, attempted payload bytes `payload_bytes`, `usage_queries`, and processed `usage_events`. They reset when the service is recreated. Compare samples over a known interval and record screen, playback and network conditions. Payload bytes exclude response/TLS overhead and failed attempts may not transmit all bytes.

For energy claims, use repeated matched baseline/optimized sessions with debugging disconnected during the measurement window. Request reduction alone is not evidence of a proportional energy reduction.

Physical-device preview check (OnePlus 13T, Android 16, 2026-10-24): during a 33-second observation window, counters increased by 7 collections, 2 HTTP attempts, 5 deduplicated snapshots and 7 usage queries; processed usage-event count stayed unchanged. This verifies suppression and incremental reading in that short window, not overnight reliability or battery savings. Installation preserved existing configuration. The server's configured TTL was verified as 45 seconds.

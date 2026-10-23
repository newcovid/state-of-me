# Android context capabilities — 0.7 development preview

All collection uses public Android APIs (min API 26); root/LSPosed are unnecessary.
The scope is current personal device/activity state, not every datum Android can expose.

| Category | Collected information | Limits |
|---|---|---|
| Application | allowlisted package; optional label and observed foreground-segment duration | last-hour UsageEvents, screen on/unlocked; multi-window/lost OEM events can make this approximate, not a daily usage report |
| Music | title, artist, album, playback status/rate/progress, lyrics/window | authorized MediaSession players; embedded MP3/FLAC/M4A lyrics or matching manual LRC; unsupported tags remain unavailable |
| Device | battery %, charging source, battery health/temperature/voltage, screen/lock/power-save | temperature is battery temperature, not CPU; vendor reporting may be unavailable |
| Network (new) | active default network, validation, metering, transport classes | no network names, IPs or locations; system validation is a snapshot |
| Audio (new) | media stream volume/mute, ringer/DND mode, connected output classes | outputs are available devices, not proof of the actual media route or wearer |
| Resources (new) | available/total RAM and internal app-volume storage, low-memory flag, thermal severity | MiB; thermal severity 0–6 on API 29+, not degrees; excludes CPU utilization and other apps' private memory |
| Collector health (new) | app version/API level, notification/usage/music-directory grants, battery optimization exemption | permission state is diagnostic, not a guarantee of continuous collection |

## Contract and consent

New optional event objects: `network`, `audio`, `resources`, `collector_health`, `app_details`.
Each needs its Android sharing switch **and** the matching boolean under `devices.<id>` **and** the matching name in `readers.<audience>.fields` (or public.fields).
Defaults are off. `app_details` additionally requires a permitted `app` and the audience's `app` field.
`device_state` gains optional fields; `music.playback_status` is additive. Older clients continue to work.
The Pydantic schemas in server/app.py are the exact bounded wire contract. Units are in field names.
Missing/null means unshared or unavailable, never zero/false. Collection failures are isolated by category.
Disabling server rules retracts stored information immediately. Client switches take effect on the next successful update; offline snapshots expire with the configured TTL. Pause clears all categories.
Snapshots replace previous state; no activity history is added. Unicode lyric windows are trimmed at timestamp boundaries to fit the 16 KiB request limit.

AstrBot keeps identity verification and audience credentials; it receives only that audience's projection. It answers only requested fields and explains unavailable data. Live group-owner lookup remains required.

## Deliberate scope boundaries

Location, notifications/chat bodies, contacts, accounts, sensors, microphone/camera capture, installed-app inventories, browsing history and clipboard are not implemented. They are not necessary for this presence scope and would need separate product requirements and explicit consent UX.
No root-only Poweramp bridge dependency, real-time CPU telemetry, physical headphone-wearing detection, or guaranteed multi-window foreground attribution is claimed.

References: [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities), [AudioManager](https://developer.android.com/reference/android/media/AudioManager), [PowerManager](https://developer.android.com/reference/android/os/PowerManager).

## Upgrade and testing

Upgrade backend first, then AstrBot adapter and Android APK. Back up server code/config and adapter before restart. Keep public fields music/lyric/lyric_window unless intentionally changing public sharing.
0.7 local checks: 20 Python contract/identity/adapter tests; playback JS contracts; Android compilation and APK signature verification. New hardware-derived values still require physical-device use and OEM coverage. Synthetic contract tests do not establish live sensor correctness or group conversation quality.
After APK upgrade, reopen the app, choose categories in 分享 and save/start. Ask explicitly “我的网络怎么样” or “群主的电量还有多少”; test switching categories off and waiting for next update/TTL.

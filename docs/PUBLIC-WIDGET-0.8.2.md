# Public status widget — 0.8.2

The floating blog card now combines a device summary with an active-music section. It inherits the blog's colors and retains default collapse, saved collapse/lyrics visibility/position, drag/keyboard movement and narrow-screen sizing.

- Device summary: application label (package identifier fallback), battery percentage, charging and locked indicators. Missing/false flags do not produce a list of negative statements.
- Music appears only when `playing=true` and no explicit nonplaying state contradicts it. Paused, stopped and expired tracks clear title, artist, album, lyrics and progress together. Basic state stays visible.
- Active music includes artist, album, cached current/next lyrics, progress, total/remaining time. The card displays the oldest included snapshot's synchronization time; this is not proof the person is online.
- Each device expires independently. Data is textContent-only; no raw activity is stored in localStorage.

## Public scopes

The user approved publishing app names, battery, charging and locked state on the blog. This deployment retains the existing public device list and music scopes and adds:

```json
{
  "fields": ["music", "lyric", "lyric_window", "app", "app_details", "device_state"],
  "app_details_fields": ["label"],
  "device_state_fields": ["battery_percent", "charging", "locked"]
}
```

`<category>_fields` is an optional audience subfield allowlist, applied again at read time. An empty list withdraws that category. Omitted subfield scopes retain existing audience behavior. Temperature, voltage, app duration, network, audio and diagnostic information are not public. Device collection switches and filters still apply; this change does not turn them on or publish additional devices.

## Validation

30 Python contract/adapter tests, playback JS tests and new widget model tests passed. Tests cover zero battery, absent/false fields, expiry, paused/conflicting music and subfield withdrawal. In a browser fixture, active music and basic state were rendered, then a paused snapshot removed the entire music section while preserving the basic state. Live blog shows battery/charging, and both light and dark theme transitions were visually verified. Original collapsed/theme preference restored after verification. Private screenshots remain outside Git.

Deployment backs up server sources and configuration before restarting only State of Me. No Android or AstrBot upgrade is needed for this web change.

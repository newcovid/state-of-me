# 0.8 UX review and implementation

## Design brief

Native Android Views implementing a miuix-inspired visual language, not the Compose Miuix library. Retain the existing small Java build and collector. Palette: background #F5F5F5, surface #FFFFFF, text #191919, secondary #737373, accent #3482FF; dark counterpart #000000 / #1C1C1E. System sans font respects user settings. Large left-aligned title, flat segmented navigation, rounded setting groups, 48dp+ touch targets. Avoid default Button elevation/background artifacts.

Flow: connection (save once) → sharing (select apps and permissions) → optional music folder → start/pause. Review against the brief removed repeated diagnostics, per-track configuration and advanced switches from the main path.

## Independent user-perspective review and fixes

A requested subagent inspected App/bot code and operated the live blog card. It did not send QQ messages or change phone permissions. Findings addressed:

- Package strings → names/icons/search/multi-selection. Keep separate allow/block sets; cancellation discards edits; empty allowlist shares nothing.
- Hidden apps in denylist mode → only saved visible candidates can be shared. Newly installed apps require reopening/saving the picker. Launcher apps, active media sessions and previous selections are candidates. No QUERY_ALL_PACKAGES permission or inventory upload.
- Mixed saving model → connection saves independently, range toggles save immediately, picker has explicit save. Home only starts/pauses. Connection drafts survive tab changes and recreation.
- Single-song LRC and duplicate revoke controls → remove both UI and collector fallback; a music folder is the daily path. Keep refresh and confirmed remove operations. Removed-folder lyric status is cleared.
- Dense telemetry → app/music first, less common telemetry behind More. Permission summary reports music and usage access.
- Navigation dirty edges → TextView segments with explicit backgrounds; no platform Button elevation. Native dialog accents and dark theme aligned. Button accessibility roles retained.
- Picker search/keyboard → empty-result guidance, selected apps first, compress mode/hint while keyboard takes height, scrollable result list.
- Blog reset-position symbol looked like repeat → location symbol and explicit accessible label; footer simplified.
- Bot empty/error response → distinguish transport error from no readable fresh data, provide a short check-the-app suggestion without asserting user is offline.

## Validation boundaries

23 Python contract/identity/adapter tests, Java filter cases, JS playback contracts and signed APK build pass. Main agent installed the candidate and captured actual home, sharing and app-selection screens; navigation shadows are absent. Device uses a customized font/input method: observed floating composition, not a standard full-height keyboard. Full-height IME, dark mode and accessibility behavior still need user acceptance; implemented layout handling is not claimed as complete OEM coverage.

Subagent actually operated blog collapse/lyrics controls and restored their state. Bot verification is code/contracts, not a live group exchange. Private screenshots contain installed-app information and stay out of the public repository.

Server filtering defaults remain backward-compatible whitelist. This deployment lets the phone's local selections govern candidate apps/players via server blacklist mode with no server exclusions; other devices and audience field scopes stay unchanged. Administrators can retain a stricter server ceiling. Public viewers still only receive music/lyrics, never application or device context.

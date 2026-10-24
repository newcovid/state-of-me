# Android UI

The Android application uses upstream [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4 from Maven Central. Versions are pinned in `clients/android/build.gradle.kts`.

| Interface | Upstream components |
| --- | --- |
| Theme, layout, navigation | MiuixTheme, Scaffold, TopAppBar, TabRow |
| Settings groups and actions | Card, ArrowPreference, SwitchPreference |
| Application selection | WindowDialog, TabRow, TextField, CheckboxPreference |
| Connection and confirmation | TextField, Button, WindowDialog |

Kotlin/Compose owns the UI. Existing Java collectors, filters, embedded lyric readers and SharedPreferences remain independent. Package identity and existing signing key are retained for in-place upgrades. No root access is needed.

Build with JDK 21, Android SDK platform 37.0 and build tools 37.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run `clients/android/build.ps1`. Gradle Wrapper is checked in with the distribution checksum. On other platforms, run `./gradlew assembleDebug` in `clients/android`.

Release signing uses the ignored `signing/preview.keystore`. The PowerShell script migrates an existing `build/preview.keystore` before building. Back up this key; never commit it. Gradle clean can safely remove build outputs without removing the migrated key.

Miuix publishes evolving APIs; upgrades should be explicit and include physical-device checks of light/dark mode, keyboard avoidance, picker scrolling, cancellation, and preservation of existing settings.

## 0.9.0 preview validation

- Gradle release assembly and release lint passed; application filter contracts passed.
- Same-signature replacement installed on OnePlus 13T / Android 16. All four pages opened without an AndroidRuntime crash; settings, directory authorization and the existing music index survived.
- App picker icons, selection drafts, search and cancellation were exercised. Navigation width was adjusted after inspection on the physical screen.
- Previously enabled collection resumes when reopening after package replacement; a fresh successful sync was observed.
- The current phone keyboard reported itself shown but its panel was not visible in captured frames. Full docked-keyboard occlusion and dark-mode visual acceptance remain manual checks; IME insets are handled by Compose and upstream WindowDialog.
- This is a development preview, with no claim of broad OEM coverage.

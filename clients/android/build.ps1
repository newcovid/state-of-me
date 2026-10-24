$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory' }
$key = Join-Path $PSScriptRoot 'signing/preview.keystore'
New-Item -ItemType Directory -Force (Split-Path $key) | Out-Null
$legacyKey = Join-Path $PSScriptRoot 'build/preview.keystore'
if (-not (Test-Path $key) -and (Test-Path $legacyKey)) { Copy-Item -LiteralPath $legacyKey -Destination $key }
if (-not (Test-Path $key)) {
    & keytool -genkeypair -keystore $key -storepass android -keypass android -alias preview -dname 'CN=State of Me Development Preview' -keyalg RSA -validity 3650
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed' }
}
& "$PSScriptRoot/gradlew.bat" -p $PSScriptRoot assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
Copy-Item -LiteralPath "$PSScriptRoot/build/outputs/apk/release/StateOfMeAndroid-release.apk" -Destination "$PSScriptRoot/build/state-of-me-0.9.1.apk"

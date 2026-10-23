$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK directory' }
$bt = Join-Path $sdk 'build-tools/36.0.0'
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$out = Join-Path $root 'build'
New-Item -ItemType Directory -Force "$out/classes", "$out/dex" | Out-Null
function Run([string]$exe,[string[]]$arguments) { & $exe @arguments; if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" } }
$sources = @(Get-ChildItem "$root/src" -Recurse -Filter *.java | ForEach-Object FullName)
Run 'javac' (@('-encoding','UTF-8','--release','8','-classpath',$androidJar,'-d',"$out/classes") + $sources)
Run 'jar' @('--create','--file',"$out/classes.jar",'-C',"$out/classes",'.')
Run "$bt/d8.bat" @('--lib',$androidJar,'--min-api','26','--output',"$out/dex","$out/classes.jar")
Run "$bt/aapt2.exe" @('link','-I',$androidJar,'--manifest',"$root/AndroidManifest.xml",'-o',"$out/unsigned.apk")
Run 'jar' @('--update','--file',"$out/unsigned.apk",'-C',"$out/dex",'classes.dex')
Run "$bt/zipalign.exe" @('-f','4',"$out/unsigned.apk","$out/aligned.apk")
$keystore = Join-Path $out 'preview.keystore'
if (-not (Test-Path $keystore)) { Run 'keytool' @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android','-alias','preview','-dname','CN=State of Me Development Preview','-keyalg','RSA','-validity','3650') }
Run "$bt/apksigner.bat" @('sign','--ks',$keystore,'--ks-pass','pass:android','--key-pass','pass:android','--out',"$out/state-of-me-0.6.0.apk","$out/aligned.apk")
Run "$bt/apksigner.bat" @('verify','--verbose',"$out/state-of-me-0.6.0.apk")

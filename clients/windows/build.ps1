param([string]$OutputDirectory="")
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repoRoot 'dist/windows-0.10.0'}
dotnet publish $PSScriptRoot -c Release -r win-x64 --self-contained true -o $OutputDirectory
if($LASTEXITCODE -ne 0){throw 'Desktop publish failed'}
$licenseDir=Join-Path $OutputDirectory 'licenses'
New-Item -ItemType Directory -Path $licenseDir -Force | Out-Null
$cacheLine=dotnet nuget locals global-packages --list
$packageRoot=($cacheLine -replace '^global-packages:\s*','').Trim()
Copy-Item -LiteralPath (Join-Path $packageRoot 'wpf-ui/4.3.0/LICENSE.md') -Destination (Join-Path $licenseDir 'WPF-UI-MIT.md')
Copy-Item -LiteralPath (Join-Path $packageRoot 'wpf-ui/4.3.0/ThirdPartyNotices.txt') -Destination (Join-Path $licenseDir 'WPF-UI-ThirdPartyNotices.txt')
Copy-Item -LiteralPath (Join-Path $packageRoot 'naudio/2.2.1/license.txt') -Destination (Join-Path $licenseDir 'NAudio-MIT.txt')
$source=Join-Path $repoRoot 'dist/TagLibSharp-2.3.0-source.tar.gz'
if(-not(Test-Path -LiteralPath $source)){Invoke-WebRequest 'https://github.com/mono/taglib-sharp/archive/refs/tags/TaglibSharp-2.3.0.0.tar.gz' -OutFile $source}
if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne 'C96834A945369E07F9018959E38DAFD3769237AEC8BA3FFB2F460644DA081A4E'){throw 'TagLib source checksum mismatch'}
tar -xOf $source taglib-sharp-TaglibSharp-2.3.0.0/COPYING | Set-Content -LiteralPath (Join-Path $licenseDir 'TagLibSharp-LGPL-2.1.txt') -Encoding utf8
if($LASTEXITCODE -ne 0){throw 'License extraction failed'}
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs/DESKTOP-0.10.md') -Destination (Join-Path $OutputDirectory 'README.zh-CN.md')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD-PARTY.md') -Destination $OutputDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $OutputDirectory
$runtime=Get-Content (Join-Path $OutputDirectory 'StateOfMe.runtimeconfig.json') -Raw | ConvertFrom-Json
foreach($framework in $runtime.runtimeOptions.includedFrameworks){
 $package=Join-Path $packageRoot ($framework.name.ToLowerInvariant()+'.runtime.win-x64/'+$framework.version)
 foreach($name in @('LICENSE','LICENSE.TXT','THIRD-PARTY-NOTICES.TXT')){ $license=Join-Path $package $name; if(Test-Path -LiteralPath $license){Copy-Item -LiteralPath $license -Destination (Join-Path $licenseDir ($framework.name+'-'+$name))} }
}

Write-Output "Desktop package: $OutputDirectory"

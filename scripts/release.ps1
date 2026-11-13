# Release build: Android APK installed on the phone, desktop installer copied to Downloads.
# Usage: powershell -File scripts\release.ps1 [-SkipAndroid] [-SkipDesktop]
param([switch]$SkipAndroid, [switch]$SkipDesktop)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$env:JAVA_HOME = 'D:\jdk17'
$adb = 'D:\scrcpy\adb.exe'
$iscc = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
$downloads = Join-Path $env:USERPROFILE 'Downloads'

# Only one Gradle at a time.
if (Test-Path 'D:\PaperPlayCompose.lock') { throw 'Build lock D:\PaperPlayCompose.lock exists; another build is running.' }

function Gradle($task) {
    # Windows PowerShell 5.1 turns native stderr into errors under Stop; Gradle writes its messages there.
    $ErrorActionPreference = 'Continue'
    $out = & .\gradlew.bat $task --no-daemon -q 2>&1 | ForEach-Object { "$_" }
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) {
        $out | Where-Object { $_ -match '^e: |FAILED|What went wrong' } | Select-Object -First 30 | ForEach-Object { Write-Host $_ }
        throw "Gradle $task failed"
    }
}

if (-not $SkipAndroid) {
    Write-Host 'Android release...'
    Gradle ':androidApp:assembleRelease'
    $apk = 'androidApp\build\outputs\apk\release\androidApp-release.apk'
    $devices = @(& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
    if ($devices.Count -eq 1) {
        Write-Host (& $adb -s $devices[0] install -r $apk)
    } else {
        Write-Host "Phone not installed to: $($devices.Count) devices connected. APK: $apk"
    }
}

if (-not $SkipDesktop) {
    Write-Host 'Desktop release...'
    Gradle ':desktopApp:createReleaseDistributable'
    $version = (Select-String -Path 'desktopApp\installer\PaperPlay.iss' -Pattern '#define AppVersion "(.+)"').Matches[0].Groups[1].Value
    & $iscc 'desktopApp\installer\PaperPlay.iss' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'ISCC failed' }
    $setup = Get-ChildItem 'desktopApp\build\installer' -Filter '*-setup.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Copy-Item $setup.FullName $downloads -Force
    Write-Host "Installer: $(Join-Path $downloads $setup.Name)"
}

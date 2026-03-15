param(
    [switch]$InstallApk,
    [switch]$LaunchApp,
    [switch]$CaptureLog = $true
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-e-drive-android-env.ps1"

$projectRoot = Split-Path $PSScriptRoot -Parent
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$logsDir = Join-Path $projectRoot "logs"
$packageName = "com.mayegg.anisub"
$mainActivity = "$packageName/.MainActivity"

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

Write-Output "Restarting adb server..."
& adb kill-server | Out-Null
& adb start-server | Out-Null

Write-Output "ADB version:"
& adb version

Write-Output "Connected devices:"
& adb devices -l

if (-not (Test-Path $apkPath)) {
    Write-Output "APK not found: $apkPath"
    Write-Output "Run scripts\\build-debug.ps1 first."
    exit 0
}

Write-Output "APK=$apkPath"

$deviceLines = (& adb devices) | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" }
$onlineDevices = $deviceLines | Where-Object { $_ -match "\sdevice$" -or $_ -match "\sdevice\s" }

if (-not $onlineDevices) {
    Write-Output "No online Android device detected."
    Write-Output "Connect a phone and allow USB debugging, then rerun this script."
    exit 0
}

if ($InstallApk) {
    Write-Output "Installing debug APK..."
    & adb install -r $apkPath
}

if ($LaunchApp) {
    Write-Output "Launching app..."
    & adb shell am start -n $mainActivity
}

if ($CaptureLog) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $logPath = Join-Path $logsDir "adb-logcat-$timestamp.txt"
    $latestPath = Join-Path $logsDir "adb-logcat-latest.txt"
    Write-Output "Exporting current logcat snapshot..."
    & adb logcat -d -v time | Out-File -FilePath $logPath -Encoding utf8
    Copy-Item -Force $logPath $latestPath
    Write-Output "LOG_FILE=$logPath"
    Write-Output "LATEST_LOG_FILE=$latestPath"
}

Write-Output "Ready for adb debugging."
Write-Output "Useful commands:"
Write-Output "  adb logcat"
Write-Output "  adb shell am start -n $mainActivity"
Write-Output "  adb install -r $apkPath"

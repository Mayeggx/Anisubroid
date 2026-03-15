$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-e-drive-android-env.ps1"
$env:SKIP_JDK_VERSION_CHECK = "1"

$zipPath = "E:\Temp\android\commandlinetools-win-14742923_latest.zip"
$cmdlineRoot = "E:\Android\Sdk\cmdline-tools"
$latestRoot = Join-Path $cmdlineRoot "latest"
$tmpRoot = Join-Path $cmdlineRoot "tmp"
$sdkManager = Join-Path $latestRoot "bin\sdkmanager.bat"

if (-not (Test-Path $zipPath)) {
    throw "Missing command-line tools zip: $zipPath"
}

New-Item -ItemType Directory -Force -Path $cmdlineRoot, $tmpRoot, $latestRoot | Out-Null

if (-not (Test-Path $sdkManager)) {
    if (Test-Path $tmpRoot) {
        Remove-Item $tmpRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
    Expand-Archive -Path $zipPath -DestinationPath $tmpRoot -Force
    Copy-Item (Join-Path $tmpRoot "cmdline-tools\*") $latestRoot -Recurse -Force
}

1..20 | ForEach-Object { "y" } | & $sdkManager --sdk_root=$env:ANDROID_SDK_ROOT --licenses | Out-Null
& $sdkManager --sdk_root=$env:ANDROID_SDK_ROOT "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Write-Output "sdkmanager=$sdkManager"
Write-Output "skip-jdk-version-check=$env:SKIP_JDK_VERSION_CHECK"
Write-Output "platform-tools=" + (Test-Path "E:\Android\Sdk\platform-tools\adb.exe")
Write-Output "android-34=" + (Test-Path "E:\Android\Sdk\platforms\android-34")
Write-Output "build-tools-34=" + (Test-Path "E:\Android\Sdk\build-tools\34.0.0")

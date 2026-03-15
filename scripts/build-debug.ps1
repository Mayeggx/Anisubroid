$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-e-drive-android-env.ps1"

$projectRoot = Split-Path $PSScriptRoot -Parent
$localPropertiesPath = Join-Path $projectRoot "local.properties"
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $localPropertiesPath)) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Set-Content -Encoding UTF8 $localPropertiesPath
    Write-Output "Created local.properties at $localPropertiesPath"
}

Push-Location $projectRoot
try {
    Write-Output "Starting debug build..."
    & .\gradlew.bat clean assembleDebug --console=plain --stacktrace --no-daemon
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        throw "Gradle build failed with exit code: $gradleExitCode"
    }

    if (-not (Test-Path $apkPath)) {
        throw "Build finished but APK was not found: $apkPath"
    }

    $apk = Get-Item $apkPath
    Write-Output "Build succeeded."
    Write-Output "APK=$($apk.FullName)"
    Write-Output "Size=$($apk.Length)"
    Write-Output "LastWriteTime=$($apk.LastWriteTime)"
} catch {
    Write-Error $_
    exit 1
} finally {
    Pop-Location
}

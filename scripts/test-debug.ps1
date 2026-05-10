$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-e-drive-android-env.ps1"

$projectRoot = Split-Path $PSScriptRoot -Parent
$localPropertiesPath = Join-Path $projectRoot "local.properties"

if (-not (Test-Path $localPropertiesPath)) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Set-Content -Encoding UTF8 $localPropertiesPath
    Write-Output "Created local.properties at $localPropertiesPath"
}

Push-Location $projectRoot
try {
    Write-Output "Starting debug unit tests..."
    & .\gradlew.bat testDebugUnitTest --console=plain --stacktrace --no-daemon
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        throw "Gradle tests failed with exit code: $gradleExitCode"
    }
    Write-Output "Debug unit tests succeeded."
} catch {
    Write-Error $_
    exit 1
} finally {
    Pop-Location
}

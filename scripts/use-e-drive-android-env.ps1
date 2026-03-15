$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "E:\Development\jdk17"
$env:ANDROID_HOME = "E:\Android\Sdk"
$env:ANDROID_SDK_ROOT = "E:\Android\Sdk"
$env:ANDROID_USER_HOME = "E:\Android\UserHome"
$env:ANDROID_PREFS_ROOT = $null
$env:GRADLE_USER_HOME = "E:\Gradle\user-home"
$env:TEMP = "E:\Temp\android"
$env:TMP = "E:\Temp\android"
$env:TMPDIR = "E:\Temp\android"
$env:HOME = "E:\Android\UserHome\home"
$env:_JAVA_OPTIONS = "-Djava.io.tmpdir=E:\Temp\android"
$env:HTTP_PROXY = "http://127.0.0.1:7897"
$env:HTTPS_PROXY = "http://127.0.0.1:7897"

New-Item -ItemType Directory -Force -Path `
    $env:ANDROID_HOME, `
    $env:ANDROID_USER_HOME, `
    $env:GRADLE_USER_HOME, `
    $env:TEMP, `
    $env:HOME | Out-Null

$env:Path = @(
    "$env:JAVA_HOME\bin",
    "$env:ANDROID_HOME\platform-tools",
    "$env:ANDROID_HOME\cmdline-tools\latest\bin",
    $env:Path
) -join ";"

Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
Write-Output "ANDROID_SDK_ROOT=$env:ANDROID_SDK_ROOT"
Write-Output "ANDROID_USER_HOME=$env:ANDROID_USER_HOME"
Write-Output "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Output "TEMP=$env:TEMP"
Write-Output "TMP=$env:TMP"
Write-Output "HOME=$env:HOME"
Write-Output "_JAVA_OPTIONS=$env:_JAVA_OPTIONS"
Write-Output "HTTP_PROXY=$env:HTTP_PROXY"
Write-Output "HTTPS_PROXY=$env:HTTPS_PROXY"

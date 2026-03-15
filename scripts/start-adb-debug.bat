@echo off
setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-adb-debug.ps1" -InstallApk -LaunchApp
set EXIT_CODE=%ERRORLEVEL%
echo.
if not "%EXIT_CODE%"=="0" (
  echo ADB debug script failed with exit code %EXIT_CODE%.
) else (
  echo ADB debug script completed.
)
pause
exit /b %EXIT_CODE%

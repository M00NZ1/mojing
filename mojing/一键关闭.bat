@echo off
if /i not "%~1"=="launched" (
    start "DS-Pro Stop" cmd /k "%~f0" launched
    exit /b 0
)
cd /d "%~dp0"
if not exist "scripts\stop_dev_services.ps1" (
    echo [ERROR] scripts\stop_dev_services.ps1 not found
    goto finish
)
echo Stopping backend and frontend dev processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_dev_services.ps1"
echo.
echo Done.

:finish
echo Press any key to close...
pause >nul

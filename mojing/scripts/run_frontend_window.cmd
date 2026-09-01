@echo off
title MoJing Frontend :5175
cd /d "%~dp0.."
if not exist "frontend\package.json" (
    echo [ERROR] frontend\package.json not found
    echo CWD: %cd%
    goto stay
)

echo CWD: %cd%
echo Starting frontend at http://127.0.0.1:5175
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_frontend.ps1"
if errorlevel 1 echo [ERROR] Frontend startup failed.

:stay
echo.
echo Frontend stopped. Press any key to close...
pause >nul

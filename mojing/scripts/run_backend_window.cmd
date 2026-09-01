@echo off
title MoJing Backend :8000
cd /d "%~dp0.."
if not exist "backend\app\main.py" (
    echo [ERROR] backend\app\main.py not found
    echo CWD: %cd%
    goto stay
)

echo CWD: %cd%
echo Starting backend at http://127.0.0.1:8000
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_backend.ps1"
if errorlevel 1 echo [ERROR] Backend startup failed.

:stay
echo.
echo Backend stopped. Press any key to close...
pause >nul

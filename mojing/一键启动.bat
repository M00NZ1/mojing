@echo off
if /i not "%~1"=="launched" (
    start "MoJing Launcher" cmd /k "%~f0" launched
    exit /b 0
)
cd /d "%~dp0"
set "ROOT=%cd%"
title MoJing Launcher

echo ========================================
echo   MoJing - Dev Environment
echo ========================================
echo Root: %ROOT%
echo.

if not exist "%ROOT%\backend\app\main.py" (
    echo [ERROR] Run this file from the mojing root folder.
    goto finish
)

where npm.cmd >nul 2>&1
if errorlevel 1 (
    where npm >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] npm not found. Install Node.js and add npm to PATH.
        goto finish
    )
)

echo Running environment checks and starting managed background services...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\start_dev_detached.ps1"
if errorlevel 1 (
    echo.
    echo [ERROR] Startup failed. No partial service set should remain.
    echo Check logs under: %ROOT%\logs
    goto finish
)

echo.
echo ========================================
echo   READY - Backend and frontend healthy
echo ========================================
echo Services run in background. This window can be closed safely.
echo Logs: %ROOT%\logs\backend.log and frontend.log
echo Stop: double-click the one-click stop batch in this folder.
echo.
start "" "http://127.0.0.1:5175/"

:finish
echo Press any key to close this launcher...
pause >nul

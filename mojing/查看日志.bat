@echo off
if /i not "%~1"=="launched" (
    start "DS-Pro Logs" cmd /k "%~f0" launched
    exit /b 0
)
cd /d "%~dp0"
if not exist "logs\backend.log" (
    echo No logs yet. Start services first.
    goto end
)
echo Tailing logs (Ctrl+C stops viewing only, services keep running)
echo ---
powershell -NoProfile -Command "Get-Content -LiteralPath 'logs\backend.log','logs\frontend.log' -Wait -Tail 30"

:end
pause >nul

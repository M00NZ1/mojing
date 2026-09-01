$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $root "backend\app\main.py"))) {
    Write-Error "backend\app\main.py was not found under: $root"
}

. (Join-Path $PSScriptRoot "python_runtime.ps1")
$py = Get-MoJingPythonLaunch -Root $root -RequiredModules @("uvicorn", "fastapi", "sqlalchemy", "alembic")
Set-Location $root
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
Write-Host "Python runtime: $($py.Label), Python $($py.Version)" -ForegroundColor DarkGray
$launchArgs = @($py.Prefix) + @("-m", "uvicorn", "backend.app.main:app", "--host", "127.0.0.1", "--port", "8000", "--reload")
& $py.Exe @launchArgs
exit $LASTEXITCODE

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "python_runtime.ps1")
$py = Get-MoJingPythonLaunch -Root $root -RequiredModules @("alembic", "sqlalchemy")
Set-Location $root
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
Write-Host "Python runtime: $($py.Label), Python $($py.Version)" -ForegroundColor DarkGray
$launchArgs = @($py.Prefix) + @("-m", "alembic", "upgrade", "head")
& $py.Exe @launchArgs
exit $LASTEXITCODE

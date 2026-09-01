# Interactive launcher: preflight, open backend/frontend windows, wait for readiness.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path -LiteralPath (Join-Path $root "backend\app\main.py"))) {
    Write-Error "backend\app\main.py was not found. Run this script from the mojing checkout."
}
if (-not (Test-Path -LiteralPath (Join-Path $root "frontend\package.json"))) {
    Write-Error "frontend\package.json was not found under: $root"
}

. (Join-Path $PSScriptRoot "python_runtime.ps1")
$py = Get-MoJingPythonLaunch -Root $root -RequiredModules @("uvicorn", "fastapi", "sqlalchemy", "alembic")
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npmCommand) { Write-Error "npm was not found. Install Node.js and add npm to PATH." }

Write-Host "Repository: $root" -ForegroundColor Cyan
Write-Host "Python: $($py.Label), $($py.Version)" -ForegroundColor DarkGray
Write-Host "npm: $($npmCommand.Source)" -ForegroundColor DarkGray
Write-Host ""

Write-Host "[1/2] Starting backend: http://127.0.0.1:8000" -ForegroundColor Green
Start-Process -FilePath "cmd.exe" -ArgumentList @(
    "/k", "title MoJing Backend :8000 && call `"$root\scripts\run_backend_window.cmd`""
) -WorkingDirectory $root

Write-Host "[2/2] Starting frontend: http://127.0.0.1:5175" -ForegroundColor Green
Start-Process -FilePath "cmd.exe" -ArgumentList @(
    "/k", "title MoJing Frontend :5175 && call `"$root\scripts\run_frontend_window.cmd`""
) -WorkingDirectory $root

function Wait-HttpReady([string]$Url, [int]$Attempts = 45) {
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

Write-Host "Waiting for backend health..." -ForegroundColor Yellow
$backendOk = Wait-HttpReady "http://127.0.0.1:8000/health"
Write-Host "Waiting for frontend..." -ForegroundColor Yellow
$frontendOk = Wait-HttpReady "http://127.0.0.1:5175/"

if ($backendOk) { Write-Host "Backend is ready." -ForegroundColor Green }
else { Write-Warning "Backend is not ready. Check the backend window." }
if ($frontendOk) {
    Write-Host "Frontend is ready." -ForegroundColor Green
    Start-Process "http://127.0.0.1:5175/"
} else {
    Write-Warning "Frontend is not ready. Check the frontend window."
}

Write-Host "Run the one-click stop batch to stop managed services." -ForegroundColor DarkGray

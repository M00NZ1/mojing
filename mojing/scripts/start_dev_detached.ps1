# Start backend and frontend as managed detached processes.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root "logs"
$frontendDir = Join-Path $root "frontend"

function Test-PortListening([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-ProcessInfo([int]$ProcessId) {
    return Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
}

function Test-ManagedCommand([object]$ProcessInfo, [string]$Kind) {
    if (-not $ProcessInfo) { return $false }
    $commandLine = [string]$ProcessInfo.CommandLine
    if ($Kind -eq "backend") { return $commandLine -match "uvicorn" -and $commandLine -match "backend[.]app[.]main:app" }
    if ($Kind -eq "frontend") { return $commandLine -match "npm(?:[.]cmd)? run dev" -or $commandLine -match "node_modules.*vite" }
    return $false
}

function Stop-ProcessTree([int]$ProcessId) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) { Stop-ProcessTree -ProcessId ([int]$child.ProcessId) }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Stop-ManagedPidFile([string]$PidFile, [string]$Kind) {
    if (-not (Test-Path -LiteralPath $PidFile)) { return }
    $raw = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    if ($raw -notmatch "^\d+$") { return }
    $processId = [int]$raw
    $processInfo = Get-ProcessInfo -ProcessId $processId
    if (-not $processInfo) { return }
    if (-not (Test-ManagedCommand -ProcessInfo $processInfo -Kind $Kind)) {
        Write-Warning "Ignored stale $Kind PID file because PID $processId is not a matching managed process."
        return
    }
    Write-Host "Stopping previous managed $Kind process tree, PID=$processId"
    Stop-ProcessTree -ProcessId $processId
    Start-Sleep -Milliseconds 500
}

function Wait-HttpReady([string]$Url, [System.Diagnostics.Process]$Process, [int]$Attempts = 45) {
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $Process.Refresh()
        if ($Process.HasExited) { return $false }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

if (-not (Test-Path -LiteralPath (Join-Path $root "backend\app\main.py"))) {
    Write-Error "backend\app\main.py was not found under: $root"
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "package.json"))) {
    Write-Error "frontend\package.json was not found under: $root"
}

. (Join-Path $PSScriptRoot "python_runtime.ps1")
$py = Get-MoJingPythonLaunch -Root $root -RequiredModules @("uvicorn", "fastapi", "sqlalchemy", "alembic")
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npmCommand) { Write-Error "npm was not found. Install Node.js and add npm to PATH." }

$backendPidFile = Join-Path $logDir "backend.pid"
$frontendPidFile = Join-Path $logDir "frontend.pid"
$backendLog = Join-Path $logDir "backend.log"
$frontendLog = Join-Path $logDir "frontend.log"
$backendErr = Join-Path $logDir "backend.err.log"
$frontendErr = Join-Path $logDir "frontend.err.log"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Stop-ManagedPidFile -PidFile $backendPidFile -Kind "backend"
Stop-ManagedPidFile -PidFile $frontendPidFile -Kind "frontend"

if (Test-PortListening 8000) { throw "Port 8000 is already in use. Stop the existing service before launching MoJing." }
if (Test-PortListening 5175) { throw "Port 5175 is already in use. Stop the existing service before launching MoJing." }

if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "node_modules"))) {
    Write-Host "Installing locked frontend dependencies before service startup..."
    Push-Location $frontendDir
    try {
        if (Test-Path -LiteralPath "package-lock.json") {
            & $npmCommand.Source ci --no-audit --no-fund
        } else {
            & $npmCommand.Source install --no-audit --no-fund
        }
        if ($LASTEXITCODE -ne 0) { throw "Frontend dependency installation failed." }
    } finally {
        Pop-Location
    }
}

$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
$backendProc = $null
$frontendProc = $null

try {
    $uvicornArgs = @($py.Prefix) + @(
        "-m", "uvicorn", "backend.app.main:app",
        "--host", "127.0.0.1", "--port", "8000", "--reload"
    )
    Write-Host "[1/2] Starting backend with $($py.Label), Python $($py.Version)"
    $backendProc = Start-Process -FilePath $py.Exe -ArgumentList $uvicornArgs `
        -WorkingDirectory $root `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErr `
        -WindowStyle Hidden `
        -PassThru
    $backendProc.Id | Out-File -LiteralPath $backendPidFile -Encoding ascii -NoNewline
    if (-not (Wait-HttpReady -Url "http://127.0.0.1:8000/health" -Process $backendProc)) {
        throw "Backend did not become healthy. Inspect logs\backend.err.log."
    }

    Write-Host "[2/2] Starting frontend"
    $frontendCmd = "/c npm.cmd run dev 1>> `"$frontendLog`" 2>> `"$frontendErr`""
    $frontendProc = Start-Process -FilePath "cmd.exe" -ArgumentList $frontendCmd `
        -WorkingDirectory $frontendDir `
        -WindowStyle Hidden `
        -PassThru
    $frontendProc.Id | Out-File -LiteralPath $frontendPidFile -Encoding ascii -NoNewline
    if (-not (Wait-HttpReady -Url "http://127.0.0.1:5175/" -Process $frontendProc)) {
        throw "Frontend did not become healthy. Inspect logs\frontend.err.log."
    }
} catch {
    if ($frontendProc -and -not $frontendProc.HasExited) { Stop-ProcessTree -ProcessId $frontendProc.Id }
    if ($backendProc -and -not $backendProc.HasExited) { Stop-ProcessTree -ProcessId $backendProc.Id }
    Remove-Item -LiteralPath $frontendPidFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $backendPidFile -Force -ErrorAction SilentlyContinue
    throw
}

Write-Host ""
Write-Host "MoJing is ready." -ForegroundColor Green
Write-Host "Backend: http://127.0.0.1:8000"
Write-Host "Frontend: http://127.0.0.1:5175"
Write-Host "Logs: logs\backend.log and logs\frontend.log"
Write-Host "Stop: run the one-click stop batch."

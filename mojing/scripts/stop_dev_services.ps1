# Stop only processes managed by this checkout. Never kill unrelated port owners.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root "logs"

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
    if ($raw -notmatch "^\d+$") {
        Write-Warning "Removed invalid $Kind PID file."
        return
    }
    $processId = [int]$raw
    $processInfo = Get-ProcessInfo -ProcessId $processId
    if (-not $processInfo) { return }
    if (-not (Test-ManagedCommand -ProcessInfo $processInfo -Kind $Kind)) {
        Write-Warning "PID $processId no longer matches the managed $Kind command; it was not stopped."
        return
    }
    Write-Host "Stopping managed $Kind process tree, PID=$processId"
    Stop-ProcessTree -ProcessId $processId
}

Write-Host "Stopping services managed by this checkout..."
Stop-ManagedPidFile -PidFile (Join-Path $logDir "backend.pid") -Kind "backend"
Stop-ManagedPidFile -PidFile (Join-Path $logDir "frontend.pid") -Kind "frontend"
Start-Sleep -Milliseconds 500

foreach ($port in @(8000, 5175)) {
    $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($listener in $listeners) {
        $processInfo = Get-ProcessInfo -ProcessId ([int]$listener.OwningProcess)
        $commandLine = if ($processInfo) { [string]$processInfo.CommandLine } else { "unknown" }
        Write-Warning "Port $port is still owned by PID $($listener.OwningProcess). It was preserved because it is not proven to be managed by this checkout. Command: $commandLine"
    }
}

Write-Host "Done."

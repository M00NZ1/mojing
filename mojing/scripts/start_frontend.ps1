$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $root "frontend"
if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "package.json"))) {
    Write-Error "frontend\package.json was not found under: $root"
}

$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npmCommand) { Write-Error "npm was not found. Install Node.js and add npm to PATH." }

Set-Location $frontendDir
if (-not (Test-Path -LiteralPath "node_modules")) {
    Write-Host "node_modules is missing; installing locked dependencies..." -ForegroundColor Yellow
    if (Test-Path -LiteralPath "package-lock.json") {
        & $npmCommand.Source ci --no-audit --no-fund
    } else {
        & $npmCommand.Source install --no-audit --no-fund
    }
    if ($LASTEXITCODE -ne 0) { throw "Frontend dependency installation failed." }
}

& $npmCommand.Source run dev
exit $LASTEXITCODE

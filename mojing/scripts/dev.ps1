$root = Split-Path -Parent $PSScriptRoot
Start-Process powershell -ArgumentList "-NoExit", "-File", (Join-Path $PSScriptRoot 'start_backend.ps1')
Start-Process powershell -ArgumentList "-NoExit", "-File", (Join-Path $PSScriptRoot 'start_frontend.ps1')

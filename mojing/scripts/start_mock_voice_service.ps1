$ErrorActionPreference = "Stop"

Set-Location (Split-Path -Parent $PSScriptRoot)

uvicorn tools.voice_mock_service.mock_voice_service:app --host 127.0.0.1 --port 8010

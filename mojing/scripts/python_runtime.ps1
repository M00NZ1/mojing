$script:MoJingSupportedPythonMessage = "Python 3.11-3.13 is supported for the core backend. Coqui TTS requires Python 3.11."

function Get-MoJingPythonLaunch {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string[]]$RequiredModules = @()
    )

    $candidates = New-Object System.Collections.Generic.List[object]
    if ($env:MOJING_PYTHON) {
        $candidates.Add([pscustomobject]@{ Label = "MOJING_PYTHON"; Exe = $env:MOJING_PYTHON; Prefix = @() })
    }

    $venvPython = Join-Path $Root ".venv\Scripts\python.exe"
    if (Test-Path -LiteralPath $venvPython) {
        $candidates.Add([pscustomobject]@{ Label = ".venv"; Exe = $venvPython; Prefix = @() })
    }

    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $candidates.Add([pscustomobject]@{ Label = "PATH python"; Exe = $pythonCommand.Source; Prefix = @() })
    }

    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($pyCommand) {
        foreach ($version in @("3.13", "3.12", "3.11")) {
            $candidates.Add([pscustomobject]@{ Label = "py -$version"; Exe = $pyCommand.Source; Prefix = @("-$version") })
        }

        try {
            $launcherRows = & $pyCommand.Source -0 2>$null
            foreach ($row in $launcherRows) {
                if ($row -match '-V:([^ ]*(?:3\.(?:11|12|13)[^ ]*))') {
                    $tag = "-V:$($Matches[1])"
                    $candidates.Add([pscustomobject]@{ Label = "py $tag"; Exe = $pyCommand.Source; Prefix = @($tag) })
                }
            }
        } catch { }
    }

    $seen = @{}
    $rejected = New-Object System.Collections.Generic.List[string]
    foreach ($candidate in $candidates) {
        $key = "$($candidate.Exe)|$($candidate.Prefix -join ' ')"
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true

        $resolvedExe = $candidate.Exe
        if (-not (Test-Path -LiteralPath $resolvedExe)) {
            $command = Get-Command $resolvedExe -ErrorAction SilentlyContinue
            if (-not $command) {
                $rejected.Add("$($candidate.Label): executable not found")
                continue
            }
            $resolvedExe = $command.Source
        }

        $moduleLiteral = ($RequiredModules | ForEach-Object { "'$_'" }) -join ","
        $probeCode = @"
import importlib.util, json, sys
modules = [$moduleLiteral]
print(json.dumps({
    'version': '.'.join(map(str, sys.version_info[:3])),
    'supported': (3, 11) <= sys.version_info[:2] < (3, 14),
    'missing': [name for name in modules if importlib.util.find_spec(name) is None],
}))
"@
        try {
            $launchArgs = @($candidate.Prefix) + @("-c", $probeCode)
            $raw = (& $resolvedExe @launchArgs 2>$null | Select-Object -Last 1)
            if ($LASTEXITCODE -ne 0 -or -not $raw) { throw "runtime probe failed" }
            $probe = $raw | ConvertFrom-Json
            if (-not $probe.supported) {
                $rejected.Add("$($candidate.Label): Python $($probe.version) is unsupported")
                continue
            }
            $missing = @($probe.missing)
            if ($missing.Count -gt 0) {
                $rejected.Add("$($candidate.Label): Python $($probe.version) is missing $($missing -join ', ')")
                continue
            }
            return [pscustomobject]@{
                Label = $candidate.Label
                Exe = $resolvedExe
                Prefix = @($candidate.Prefix)
                Version = $probe.version
            }
        } catch {
            $rejected.Add("$($candidate.Label): $($_.Exception.Message)")
        }
    }

    $detail = if ($rejected.Count -gt 0) { "`n- " + ($rejected -join "`n- ") } else { "`n- no python or py command found" }
    throw "No usable Python runtime was found. $script:MoJingSupportedPythonMessage Install backend\requirements.txt into a supported environment.$detail`nYou can set MOJING_PYTHON to an explicit python.exe path."
}

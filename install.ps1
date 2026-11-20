# Install chainpoi into JOSM: copy the jar and enable it in preferences.
# JOSM must be closed, otherwise it overwrites preferences.xml on exit.
#   powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$jar = Join-Path $root 'chainpoi.jar'
$pluginDir = Join-Path $env:APPDATA 'JOSM\plugins'
$prefs = Join-Path $env:APPDATA 'JOSM\preferences.xml'
$name = 'chainpoi'

if (-not (Test-Path -LiteralPath $jar)) { throw "build first: $jar is missing" }
if (-not (Test-Path -LiteralPath $pluginDir)) { throw "no JOSM plugin dir: $pluginDir" }
if (-not (Test-Path -LiteralPath $prefs)) { throw "no JOSM preferences: $prefs" }

$running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'josm' })
if ($running.Count -gt 0) {
    throw "JOSM is still running (PID $($running.ProcessId -join ', ')). Close it gracefully (Alt+F4 or tools/close-josm.ps1) and run again."
}

# 1. backup + copy
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item -LiteralPath $prefs -Destination "$prefs.bak-$stamp" -Force
Copy-Item -LiteralPath $jar -Destination (Join-Path $pluginDir "$name.jar") -Force
Write-Output ("copied: " + (Join-Path $pluginDir "$name.jar"))

# 2. enable the plugin in preferences
$text = [System.IO.File]::ReadAllText($prefs)
$entry = "<entry value='$name'/>"
if ($text.Contains($entry)) {
    Write-Output "already enabled in preferences"
} else {
    $anchor = "<list key='plugins'>"
    if (-not $text.Contains($anchor)) { throw "cannot find <list key='plugins'> in $prefs" }
    $text = $text.Replace($anchor, $anchor + "`r`n" + $entry)
    [System.IO.File]::WriteAllText($prefs, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "enabled in preferences"
}

Write-Output "done - start JOSM"

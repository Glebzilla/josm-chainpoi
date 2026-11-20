# Runs the plugin tests without JOSM.
#   powershell -NoProfile -ExecutionPolicy Bypass -File run-tests.ps1
# Needs chainpoi.jar and josm-latest.jar (paths below).

$ErrorActionPreference = 'Stop'

# Java writes UTF-8; make PowerShell decode the pipe the same way.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$jar = 'C:\Users\gleb\Downloads\josm-latest.jar'
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
$out = Join-Path $PSScriptRoot 'out'
$report = Join-Path $PSScriptRoot 'report.txt'

if (-not (Test-Path -LiteralPath $jar)) { throw "missing $jar" }
if (-not (Test-Path -LiteralPath (Join-Path $root 'chainpoi.jar'))) { throw 'build first: chainpoi.jar' }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
Remove-Item -LiteralPath $report -ErrorAction SilentlyContinue

$cp = "$jar;$root\chainpoi.jar;$out"
$tests = @('TestFetch', 'TestMatch', 'TestApply', 'TestReal')

$sources = @(Get-ChildItem -Path $PSScriptRoot -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" --release 17 -encoding UTF-8 -cp "$jar;$root\chainpoi.jar" -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

foreach ($t in $tests) {
    $tmp = Join-Path $out "$t.out.txt"
    $tmpErr = Join-Path $out "$t.err.txt"
    # Start-Process writes the child's bytes as-is: no console re-encoding.
    $argLine = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "' + $cp + '" ' + $t
    Start-Process -FilePath "$jdk\bin\java.exe" -ArgumentList $argLine `
        -RedirectStandardOutput $tmp -RedirectStandardError $tmpErr -NoNewWindow -Wait

    Add-Content -LiteralPath $report -Value "=== $t ===" -Encoding utf8
    if (Test-Path -LiteralPath $tmp) {
        Get-Content -LiteralPath $tmp -Encoding UTF8 | Add-Content -LiteralPath $report -Encoding utf8
    }
    if ((Test-Path -LiteralPath $tmpErr) -and (Get-Item -LiteralPath $tmpErr).Length -gt 0) {
        Get-Content -LiteralPath $tmpErr -Encoding UTF8 | Add-Content -LiteralPath $report -Encoding utf8
    }
}

Write-Output ("report: " + $report)
Get-Content -LiteralPath $report

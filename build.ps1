# Build the chainpoi JOSM plugin without Gradle:
# javac against josm-latest.jar, then package with jar.
#   powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$jar = 'C:\Users\gleb\Downloads\josm-latest.jar'
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
$out = Join-Path $root 'build'
$target = Join-Path $root 'chainpoi.jar'

if (-not (Test-Path -LiteralPath $jar)) { throw "missing $jar" }
if (-not (Test-Path -LiteralPath "$jdk\bin\javac.exe")) { throw "missing JDK in $jdk" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @(Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Output ("sources: " + $sources.Count)

& "$jdk\bin\javac.exe" --release 17 -encoding UTF-8 -Xlint:all -cp $jar -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

$res = Join-Path $root 'resources'
if (Test-Path -LiteralPath $res) {
    Copy-Item -Path (Join-Path $res '*') -Destination $out -Recurse -Force
}

& "$jdk\bin\jar.exe" --create --file $target --manifest (Join-Path $root 'manifest.txt') -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }

$len = (Get-Item -LiteralPath $target).Length
Write-Output ("built: $target  $len bytes")

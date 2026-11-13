$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($maven) {
    & $maven.Source -q package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    Write-Output "Built $(Join-Path $root 'target\kuleshato.jar')"
    exit 0
}

$sourceRoot = Join-Path $root 'src\main\java'
$resourceRoot = Join-Path $root 'src\main\resources'
$output = Join-Path $root 'target\classes'
$jar = Join-Path $root 'target\kuleshato.jar'

if (Test-Path $output) {
    Remove-Item -Recurse -Force $output
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = Get-ChildItem -Path $sourceRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) {
    throw 'No Java sources found.'
}
& javac -encoding UTF-8 -Xlint:all --release 21 -d $output $sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
if (Test-Path $resourceRoot) {
    Copy-Item -Path (Join-Path $resourceRoot '*') -Destination $output -Recurse -Force
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jar) | Out-Null
& jar --create --file $jar --main-class kuleshato.Main -C $output .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Output "Built $jar"

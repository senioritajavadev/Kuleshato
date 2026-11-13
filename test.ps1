$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($maven) {
    & $maven.Source -q test
    exit $LASTEXITCODE
}

$classes = Join-Path $root 'target\test-classes'
$mainClasses = Join-Path $root 'target\test-main-classes'
$mainSources = Join-Path $root 'src\main\java'
$resources = Join-Path $root 'src\main\resources'
$tests = Join-Path $root 'src\test\java'
$outputs = @($mainClasses, $classes)
foreach ($output in $outputs) {
    if (Test-Path $output) {
        Remove-Item -Recurse -Force $output
    }
}
$mainFiles = Get-ChildItem -Path $mainSources -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName }
if ($mainFiles.Count -eq 0) {
    throw 'No Java sources found.'
}
New-Item -ItemType Directory -Force -Path $mainClasses | Out-Null
& javac -encoding UTF-8 -Xlint:all --release 21 -d $mainClasses $mainFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
if (Test-Path $resources) {
    Copy-Item -Path (Join-Path $resources '*') -Destination $mainClasses -Recurse -Force
}
$sources = Get-ChildItem -Path $tests -Filter '*.java' -Recurse |
    Where-Object { $_.Name -ne 'TestSuiteTest.java' } |
    ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) {
    throw 'No test sources found.'
}
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 -Xlint:all --release 21 -cp $mainClasses -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$testResources = Join-Path $root 'src\test\resources'
& java.exe '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dfile.encoding=UTF-8' '-ea' '-cp' "$mainClasses;$classes;$testResources" kuleshato.TestSuite
exit $LASTEXITCODE

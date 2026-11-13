# Эталонный набор вопросов о Java-проектах.
#   .\benchmark.ps1                       — детерминированный прогон (то же, что проверяется в test.ps1 и CI)
#   .\benchmark.ps1 -Live                 — дополнительно задать вопросы локальной модели Ollama
#   .\benchmark.ps1 -Live -Model qwen3:8b -OllamaUrl http://127.0.0.1:11434
#   .\benchmark.ps1 -Live -Record         — дописать результат в benchmark-results\live.jsonl
#   .\benchmark.ps1 -Live -Record -Hardware "RTX 4070, 32 GB"   — указать оборудование для сравнения
#   .\benchmark.ps1 -Compare              — таблица сравнения моделей по benchmark-results\live.jsonl
param(
    [switch]$Live,
    [string]$Model = '',
    [string]$OllamaUrl = '',
    [switch]$Record,
    [string]$Hardware = '',
    [int]$ContextWindow = 0,
    [ValidateSet('', 'questions', 'tasks', 'reviews')]
    [string]$LiveOnly = '',
    [ValidateSet('keyhole', 'legacy')]
    [string]$Reading = 'keyhole',
    [switch]$Compare
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainClasses = Join-Path $root 'target\bench-main-classes'
$testClasses = Join-Path $root 'target\bench-test-classes'
$testResources = Join-Path $root 'src\test\resources'
foreach ($output in @($mainClasses, $testClasses)) {
    if (Test-Path $output) { Remove-Item -Recurse -Force $output }
    New-Item -ItemType Directory -Force -Path $output | Out-Null
}
$mainFiles = Get-ChildItem -Path (Join-Path $root 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 --release 21 -d $mainClasses $mainFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$resources = Join-Path $root 'src\main\resources'
if (Test-Path $resources) { Copy-Item -Path (Join-Path $resources '*') -Destination $mainClasses -Recurse -Force }
$benchFiles = @(
    (Join-Path $root 'src\test\java\kuleshato\ProjectBenchmark.java'),
    (Join-Path $root 'src\test\java\kuleshato\ScriptedOllama.java')
)
& javac -encoding UTF-8 --release 21 -cp $mainClasses -d $testClasses $benchFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$arguments = @()
if ($Live) { $arguments += '--live' }
if ($Model) { $arguments += @('--model', $Model) }
if ($OllamaUrl) { $arguments += @('--ollama-url', $OllamaUrl) }
if ($Record) { $arguments += @('--record', (Join-Path $root 'benchmark-results\live.jsonl')) }
if ($Hardware) { $arguments += @('--hardware', $Hardware) }
if ($ContextWindow -gt 0) { $arguments += @('--context-window', "$ContextWindow") }
if ($LiveOnly) { $arguments += @('--live-only', $LiveOnly) }
if ($Live) { $arguments += @('--reading', $Reading) }
if ($Compare) { $arguments = @('--compare', (Join-Path $root 'benchmark-results\live.jsonl')) }
& java.exe '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dfile.encoding=UTF-8' '-ea' '-cp' "$mainClasses;$testClasses;$testResources" kuleshato.ProjectBenchmark @arguments
exit $LASTEXITCODE

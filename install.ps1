$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path $root 'kuleshato.bat'
$jar = Join-Path $root 'target\kuleshato.jar'
$bin = Join-Path $env:LOCALAPPDATA 'Kuleshato\bin'
$command = Join-Path $bin 'Kuleshato.cmd'

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Kuleshato launcher not found: $launcher"
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Build Kuleshato first by running .\build.ps1.'
}
if (Test-Path -LiteralPath $command -PathType Leaf) {
    $existingCommand = Get-Content -LiteralPath $command -Raw
    if ($existingCommand -notmatch '(?m)^rem Kuleshato-managed launcher\r?$') {
        throw "Refusing to overwrite an existing command: $command"
    }
}

New-Item -ItemType Directory -Force -Path $bin | Out-Null
$shim = "@echo off`r`nrem Kuleshato-managed launcher`r`ncall `"%KULESHATO_HOME%\kuleshato.bat`" %*`r`nexit /b %errorlevel%`r`n"
Set-Content -LiteralPath $command -Value $shim -Encoding ASCII
[Environment]::SetEnvironmentVariable('KULESHATO_HOME', $root, 'User')
$env:KULESHATO_HOME = $root

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$entries = @()
if ($userPath) {
    $entries = @($userPath -split ';' | Where-Object { $_.TrimEnd('\') -ne $bin.TrimEnd('\') })
}
$newUserPath = (@($bin) + $entries) -join ';'
[Environment]::SetEnvironmentVariable('Path', $newUserPath, 'User')
$env:Path = "$bin;$env:Path"

Write-Output "Installed Kuleshato command: $command"
Write-Output 'The current PowerShell session can use Kuleshato now; new terminals will inherit the updated user PATH.'

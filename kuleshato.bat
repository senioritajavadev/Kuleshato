@echo off
setlocal
set "ROOT=%~dp0"
for /f "tokens=2 delims=:" %%a in ('chcp') do set "OLD_CP=%%a"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul
if not exist "%ROOT%target\kuleshato.jar" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%build.ps1"
  if errorlevel 1 (
    set "EXIT_CODE=%errorlevel%"
    goto restore_codepage
  )
)
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar "%ROOT%target\kuleshato.jar" %*
set "EXIT_CODE=%errorlevel%"
:restore_codepage
if defined OLD_CP chcp %OLD_CP% >nul
exit /b %EXIT_CODE%

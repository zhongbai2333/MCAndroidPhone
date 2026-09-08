@echo off
setlocal
where pwsh.exe >nul 2>nul
if errorlevel 1 (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\quick-test.ps1" %*
) else (
    pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\quick-test.ps1" %*
)
exit /b %ERRORLEVEL%

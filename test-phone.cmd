@echo off
setlocal
cd /d "%~dp0"
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" scripts\Dev.java %*
) else (
  java scripts\Dev.java %*
)
exit /b %errorlevel%

@echo off
setlocal enabledelayedexpansion

set BASE_DIR=%~dp0\..
set TARGET_DIR=%BASE_DIR%\target

if not "%RICBOT_JAR%"=="" (
  if exist "%RICBOT_JAR%" (
    set JAR=%RICBOT_JAR%
    goto :run
  )
)

set JAR=
if exist "%TARGET_DIR%" (
  for /f "delims=" %%F in ('dir /b /o-d "%TARGET_DIR%\Ricbot-*.jar" 2^>nul') do (
    set JAR=%TARGET_DIR%\%%F
    goto :found
  )
)
:found

if "%JAR%"=="" (
  call "%BASE_DIR%\mvnw.cmd" -q package
  for /f "delims=" %%F in ('dir /b /o-d "%TARGET_DIR%\Ricbot-*.jar" 2^>nul') do (
    set JAR=%TARGET_DIR%\%%F
    goto :run
  )
)

:run
if "%JAR%"=="" (
  echo ricbot: failed to locate built jar under %TARGET_DIR% 1>&2
  exit /b 1
)

java -jar "%JAR%" %*

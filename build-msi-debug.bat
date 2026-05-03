@echo off
REM Diagnostic build of the MSI with maximum verbosity + a preserved
REM temp directory so we can inspect what jpackage hands to WiX after
REM the build fails. Run from D:\Intelliswarm.ai\intelli-mailbox.

REM Keep WiX 3.14 on PATH for this shell (the .msi build needs light.exe).
set "PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin"

REM Make sure the jar is built first (does not require -Dinstaller).
call mvn -o package -DskipTests -Dinstaller=false
if errorlevel 1 (
    echo Maven build failed. Aborting.
    goto :eof
)

REM Wipe and recreate a known-location temp dir so we can inspect the wxs files.
if exist target\jpackage-temp rd /s /q target\jpackage-temp
mkdir target\jpackage-temp

REM Wipe a clean output dir.
if exist target\installers rd /s /q target\installers
mkdir target\installers

REM Run jpackage in verbose mode so light.exe's stderr is captured.
"C:\Program Files\Java\jdk-24\bin\jpackage.exe" ^
    --type msi ^
    --name IntelliMailbox ^
    --app-version 0.1.0 ^
    --vendor IntelliSwarm.AI ^
    --input target ^
    --main-jar intelli-mailbox-0.1.0.jar ^
    --license-file LICENSE ^
    --dest target\installers ^
    --temp target\jpackage-temp ^
    --verbose

echo.
echo ============================================================
echo jpackage finished. Exit code was: %errorlevel%
echo Temp dir preserved at:  target\jpackage-temp
echo ============================================================

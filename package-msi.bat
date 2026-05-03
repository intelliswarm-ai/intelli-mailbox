@echo off
REM Diagnostic jpackage call only — no Maven, uses the jar that's already
REM in target/. Outputs to target\installers and dumps everything to debug.log.

set "PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin"

if exist target\jpackage-temp rd /s /q target\jpackage-temp
if exist target\installers rd /s /q target\installers

mkdir target\jpackage-temp
mkdir target\installers

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
echo jpackage exit code: %errorlevel%
echo Temp dir preserved at: target\jpackage-temp
echo Output dir at:         target\installers
echo ============================================================

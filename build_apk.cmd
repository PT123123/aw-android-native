@echo off
setlocal
cd /d "%~dp0"
set "PATH=%USERPROFILE%\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;%PATH%"
call gradlew.bat :mobile:assembleDebug --console=plain
echo [apk exit=%ERRORLEVEL%]
dir /b mobile\build\outputs\apk\debug\*.apk 2>nul
pause

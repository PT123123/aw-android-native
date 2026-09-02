@echo off
setlocal
cd /d <USER_HOME>\Desktop\aw-android
set "PATH=<USER_HOME>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;%PATH%"
call gradlew.bat :mobile:assembleDebug --console=plain
echo [apk exit=%ERRORLEVEL%]
dir /b mobile\build\outputs\apk\debug\*.apk 2>nul
pause

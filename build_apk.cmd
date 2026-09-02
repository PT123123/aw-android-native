@echo off
setlocal
cd /d C:\Users\<user>\Desktop\aw-android
set "PATH=C:\Users\<user>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;%PATH%"
call gradlew.bat :mobile:assembleDebug --console=plain
echo [apk exit=%ERRORLEVEL%]
dir /b mobile\build\outputs\apk\debug\*.apk 2>nul
pause

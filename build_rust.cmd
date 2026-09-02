@echo off
setlocal
cd /d <USER_HOME>\Desktop\aw-android
set "PATH=<USER_HOME>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none;%PATH%"
call gradlew.bat --stop
call gradlew.bat :mobile:cargoBuildArm --console=plain
echo [arm exit=%ERRORLEVEL%]
call gradlew.bat :mobile:cargoBuildArm64 --console=plain
echo [arm64 exit=%ERRORLEVEL%]
dir /b <USER_HOME>\Desktop\aw-android\aw-server-rust\target\armv7-linux-androideabi\release\libaw_server.so 2>nul
dir /b <USER_HOME>\Desktop\aw-android\aw-server-rust\target\aarch64-linux-android\release\libaw_server.so 2>nul
pause

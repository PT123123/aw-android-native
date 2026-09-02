$ErrorActionPreference = 'Continue'
$py312dir = '<USER_HOME>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none'
$env:PATH = $py312dir + ';' + $env:PATH
cd <USER_HOME>\Desktop\aw-android
& .\gradlew.bat --stop | Out-Null
Write-Output "=== cargoBuildArm ==="
& .\gradlew.bat :mobile:cargoBuildArm --console=plain 2>&1 | Out-String | Write-Output
Write-Output "=== EXIT: $LASTEXITCODE ==="

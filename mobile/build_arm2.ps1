$ErrorActionPreference = 'Continue'
$py312dir = 'C:\Users\<user>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none'
$env:PATH = $py312dir + ';' + $env:PATH
cd C:\Users\<user>\Desktop\aw-android
& .\gradlew.bat --stop | Out-Null
Write-Output "=== cargoBuildArm ==="
& .\gradlew.bat :mobile:cargoBuildArm --console=plain 2>&1 | Out-String | Write-Output
Write-Output "=== EXIT: $LASTEXITCODE ==="

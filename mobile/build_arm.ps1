$ErrorActionPreference = 'Continue'
$py312 = 'C:\Users\<user>\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none\python.exe'
Write-Output "=== 3.12 python pipes check ==="
& $py312 -c "import pipes; print('pipes OK in 3.12')"

Write-Output "=== PATH prepend ==="
$env:PATH = (Split-Path $py312) + ';' + $env:PATH

Write-Output "=== stop gradle daemon ==="
cd C:\Users\<user>\Desktop\aw-android
& .\gradlew.bat --stop | Out-String | Write-Output

Write-Output "=== which python in new shell ==="
Get-Command python | Select-Object -ExpandProperty Source

Write-Output "=== run cargoBuildArm ==="
& .\gradlew.bat :mobile:cargoBuildArm --console=plain 2>&1 | Out-String | Write-Output
Write-Output "=== EXIT: $LASTEXITCODE ==="

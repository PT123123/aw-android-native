$ErrorActionPreference = 'Continue'
$py312dir = "$env:USERPROFILE\AppData\Roaming\uv\python\cpython-3.12.14-windows-x86_64-none"
$env:PATH = $py312dir + ';' + $env:PATH
cd (Split-Path $PSScriptRoot -Parent)     # 仓库根
& .\gradlew.bat --stop | Out-Null
Write-Output "=== cargoBuildArm ==="
& .\gradlew.bat :mobile:cargoBuildArm --console=plain 2>&1 | Out-String | Write-Output
Write-Output "=== EXIT: $LASTEXITCODE ==="

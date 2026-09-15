# Bump the Android app version before each build.
#
#   versionName "X.Y.Z"  -> patch Z + 1   (e.g. 0.13.0 -> 0.13.1)
#   versionCode  N       -> N + 1          (e.g. 35 -> 36)
#
# Edits mobile/build.gradle in place, so the bump persists across builds.
#
# Usage: powershell/pwsh -File scripts/bump_version.ps1 [path/to/build.gradle]
param([string]$GradleFile = "mobile/build.gradle")

if (-not (Test-Path $GradleFile)) {
    Write-Error "version file not found: $GradleFile"
    exit 1
}

$path = (Resolve-Path $GradleFile).Path
$content = [System.IO.File]::ReadAllText($path)

# --- read current values ---
$vn = [regex]::Match($content, 'versionName "(\d+)\.(\d+)\.(\d+)"')
$vc = [regex]::Match($content, 'versionCode (\d+)')
if (-not $vn.Success -or -not $vc.Success) {
    Write-Error "could not find versionName/versionCode in $GradleFile"
    exit 1
}

$maj   = [int]$vn.Groups[1].Value
$min   = [int]$vn.Groups[2].Value
$pat   = [int]$vn.Groups[3].Value
$vcode = [int]$vc.Groups[1].Value
$oldName = "$maj.$min.$pat"

# --- bump ---
$pat++
$nvname = "$maj.$min.$pat"
$nvcode = $vcode + 1

# --- write back in place ---
$content = $content -replace 'versionName "\d+\.\d+\.\d+"', ("versionName `"{0}`"" -f $nvname)
$content = $content -replace 'versionCode \d+', ("versionCode {0}" -f $nvcode)

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($path, $content, $utf8NoBom)

Write-Host "bumped $GradleFile : versionName $oldName -> $nvname ; versionCode $vcode -> $nvcode"

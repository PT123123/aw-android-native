# 把 assembleRelease 的产物整理成 dist/aw-android.apk（**固定文件名**，这样
# releases/latest/download/aw-android.apk 这条直链永远指向最新一版），并在发布前自检：
#   1) 签名证书不是 Android Debug（debug key 会让已安装的人无法覆盖升级）
#   2) 包名是 dev.pt123123.awandroid（不是上游的 net.activitywatch.android）
#   3) versionCode / versionName 与 mobile/build.gradle 一致
#
# 用法: pwsh -NoLogo -NoProfile -File scripts/make_release.ps1 [-SkipVerify]
param([switch]$SkipVerify)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- 版本号 ----
$gradleText = Get-Content "mobile/build.gradle" -Raw
$verMatch = [regex]::Match($gradleText, 'versionName "([0-9]+\.[0-9]+\.[0-9]+)"')
$codeMatch = [regex]::Match($gradleText, 'versionCode ([0-9]+)')
if (-not $verMatch.Success -or -not $codeMatch.Success) { throw "读不到 mobile/build.gradle 里的 versionName/versionCode" }
$ver = $verMatch.Groups[1].Value
$vcode = $codeMatch.Groups[1].Value

# ---- 源 APK：签名成功是 mobile-release.apk，没配 keystore 时是 -unsigned ----
$signed = "mobile/build/outputs/apk/release/mobile-release.apk"
$unsigned = "mobile/build/outputs/apk/release/mobile-release-unsigned.apk"
if (Test-Path $signed) {
    $src = $signed
} elseif (Test-Path $unsigned) {
    $src = $unsigned
    Write-Host "!! 找不到已签名包，只找到 $unsigned —— keystore.properties 是不是不在？" -ForegroundColor Yellow
} else {
    throw "找不到 release APK（$signed / $unsigned），先跑 assembleRelease"
}

New-Item -ItemType Directory -Force -Path "dist" | Out-Null
Copy-Item $src "dist/aw-android.apk" -Force
Copy-Item $src "dist/aw-android-$ver.apk" -Force

# ---- 自检 ----
if (-not $SkipVerify) {
    if (-not $env:ANDROID_HOME) { throw '需要设置 $env:ANDROID_HOME' }
    $btRoot = Join-Path $env:ANDROID_HOME "build-tools"
    if (-not (Test-Path $btRoot)) { throw "找不到 $btRoot" }
    $bt = Get-ChildItem $btRoot -Directory |
        Where-Object { $_.Name -match '^[0-9]+\.[0-9]+\.[0-9]+' } |
        Sort-Object { [version]([regex]::Match($_.Name, '^[0-9]+\.[0-9]+\.[0-9]+').Value) } |
        Select-Object -Last 1
    Write-Host "build-tools: $($bt.Name)"

    $apksigner = Join-Path $bt.FullName "apksigner.bat"
    if (-not (Test-Path $apksigner)) { throw "找不到 apksigner: $apksigner" }
    $certs = & $apksigner verify --print-certs "dist/aw-android.apk" 2>&1
    if ($LASTEXITCODE -ne 0) { $certs | ForEach-Object { Write-Host $_ }; throw "apksigner verify 失败" }
    $dnLine = ($certs | Select-String -Pattern "certificate DN" | Select-Object -First 1)
    if (-not $dnLine) { throw "拿不到签名证书信息" }
    Write-Host "签名证书: $dnLine"
    if ("$dnLine" -match "CN\s*=\s*Android Debug") {
        throw "❌ 这是 debug 证书签的包！已安装的人将无法覆盖升级。检查 keystore.properties / signingConfigs.release"
    }

    $aapt2 = Join-Path $bt.FullName "aapt2.exe"
    if (Test-Path $aapt2) {
        $badging = & $aapt2 dump badging "dist/aw-android.apk" 2>&1
        $pkgLine = ($badging | Select-String -Pattern "^package:" | Select-Object -First 1)
        $abiLine = ($badging | Select-String -Pattern "native-code" | Select-Object -First 1)
        $sdkLine = ($badging | Select-String -Pattern "sdkVersion" | Select-Object -First 1)
        Write-Host "$pkgLine"
        Write-Host "$sdkLine"
        Write-Host "$abiLine"
        if ("$pkgLine" -notmatch "name='dev\.pt123123\.awandroid'") {
            Write-Host "!! package name 不是 dev.pt123123.awandroid，确认下 applicationId" -ForegroundColor Yellow
        }
        if ("$pkgLine" -notmatch "versionCode='$vcode'") {
            Write-Host "!! versionCode 与 build.gradle 不一致（期望 $vcode）" -ForegroundColor Yellow
        }
    }
}

# ---- 直链 & 发布指引 ----
$slug = "PT123123/aw-android-native"
try {
    $url = git remote get-url origin 2>$null
    $m = [regex]::Match("$url", 'github\.com[:/](?<o>[^/]+)/(?<r>[^/]+?)(\.git)?$')
    if ($m.Success) { $slug = "$($m.Groups['o'].Value)/$($m.Groups['r'].Value)" }
} catch { }

$size = [math]::Round((Get-Item "dist/aw-android.apk").Length / 1MB, 1)

Write-Host ""
Write-Host "================ 发布包就绪 ================"
Write-Host "  dist/aw-android.apk        固定名（直链用）   ${size} MB"
Write-Host "  dist/aw-android-$ver.apk   同内容，带版本号留档"
Write-Host "  versionName $ver / versionCode $vcode"
Write-Host ""
Write-Host "给朋友的永久直链（自动指向最新 release）："
Write-Host "  https://github.com/$slug/releases/latest/download/aw-android.apk"
Write-Host ""
Write-Host "发布（确认后手动执行；会打 tag v$ver 并上传）："
Write-Host "  just publish"
Write-Host "============================================"

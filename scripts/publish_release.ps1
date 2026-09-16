# 把 dist/aw-android.apk 传成 GitHub Release（tag = v<versionName>）。
# 上传前会检查：
#   - gh 已安装并登录（gh auth login）
#   - dist/aw-android.apk 存在（先跑 just release）
#   - 版本号已提交且已推到 origin（否则 release 的 tag 会指向远端 main 的旧提交）
#   - tag 还没被用过
# 用法: pwsh -NoLogo -NoProfile -File scripts/publish_release.ps1 [-Notes "自定义说明"]
param([string]$Notes = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- gh ----
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "没找到 gh（GitHub CLI）。装一个，或用网页手动上传 dist/aw-android.apk" }
& gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "gh 未登录：先执行 gh auth login" }

# ---- 产物 ----
if (-not (Test-Path "dist/aw-android.apk")) { throw "dist/aw-android.apk 不存在，先跑 just release" }

$gradleText = Get-Content "mobile/build.gradle" -Raw
$ver = [regex]::Match($gradleText, 'versionName "([0-9]+\.[0-9]+\.[0-9]+)"').Groups[1].Value
$vcode = [regex]::Match($gradleText, 'versionCode ([0-9]+)').Groups[1].Value
if (-not $ver) { throw "读不到 versionName" }
$tag = "v$ver"

# ---- 工作区 / 推送状态 ----
$dirty = git status --porcelain -- mobile/build.gradle
if ($dirty) {
    throw "mobile/build.gradle 有未提交改动（版本号 bump 还没提交）。先执行：`n  git add -A; git commit -m `"release: $tag (versionCode $vcode)`"`n  git push"
}
$head = (git rev-parse HEAD).Trim()
$remote = (git rev-parse origin/main 2>$null)
if ($LASTEXITCODE -ne 0) { throw "拿不到 origin/main" }
if ($head -ne $remote.Trim()) {
    throw "本地 HEAD 与 origin/main 不一致（有未推送提交）。release 的 tag 要指向已推送的提交，先 `"git push`"，或手动 `"git push origin $tag`""
}

# ---- tag 是否已存在 ----
git rev-parse -q --verify "refs/tags/$tag" *> $null
if ($LASTEXITCODE -eq 0) { throw "本地已有 tag $tag —— 版本号需要 +1（跑 just release）" }
& gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) { throw "远端已有 release $tag" }

# ---- 说明 ----
if (-not $Notes) {
    $Notes = @"
aw-android $ver (versionCode $vcode)

- 覆盖安装即可，数据保留（同签名、同包名）。
- 需要 Android 7.0+（minSdk 24），内置 arm64-v8a / armeabi-v7a。
- 如果装不上：先卸载手机里签名的旧版本/官方 ActivityWatch（包名不同则为其它冲突）。
- 下载地址（永久直链）：https://github.com/PT123123/aw-android-native/releases/latest/download/aw-android.apk
"@
}
New-Item -ItemType Directory -Force -Path "dist" | Out-Null
$notesFile = "dist/release-notes.md"
Set-Content -Path $notesFile -Value $Notes -Encoding UTF8

# ---- 上传（含带版本号的留档副本）----
$assets = @("dist/aw-android.apk")
$archived = "dist/aw-android-$ver.apk"
if (Test-Path $archived) { $assets += $archived }

& gh release create $tag @assets --title $tag --notes-file $notesFile --target $head
if ($LASTEXITCODE -ne 0) { throw "gh release create 失败" }

Write-Host ""
Write-Host "已发布 $tag"
Write-Host "  给朋友的直链: https://github.com/PT123123/aw-android-native/releases/latest/download/aw-android.apk"
Write-Host "  想改说明:     gh release edit $tag --notes-file $notesFile"

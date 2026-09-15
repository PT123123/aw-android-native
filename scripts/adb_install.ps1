# 安装 APK 到 adb 设备（pick_device.ps1 的配套）。
#
# 主通道就是 adb install -r（流式安装）。只有 MIUI/HyperOS 明确拦截安装通道
# （Failure [INSTALL_FAILED_USER_RESTRICTED] 等）时，才降级为 adb push + pm install，
# 后者走 shell 通道可以绕过该限制（HyperOS / Android 15 真机实测）。
#
# 其他失败（no devices / offline / unauthorized / 别的 INSTALL_FAILED_*）一律不降级，
# 直接报真实原因——盲目降级会把「设备掉线」误报成「被 ROM 拦截」，日志误导排查。
#
# HyperOS 息屏 / USB 挂起时 adb 会短暂「no devices」，这里先轮询等就绪。
#
# Usage: pwsh/powershell -File scripts/adb_install.ps1 -Apk <apk> [-Serial <serial>]
#   Serial 省略时由 adb 自行选择（仅连一台设备时够用）
#   ADB_WAIT_SECS 可覆盖等待就绪的秒数（默认 20）
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [string]$Serial = ""
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Justfile 里 export 了 ADB 绝对路径；直接手动跑时退回 PATH 里的 adb
$adb = if ($env:ADB) { $env:ADB } else { "adb" }
$wait = if ($env:ADB_WAIT_SECS) { [int]$env:ADB_WAIT_SECS } else { 20 }

if (-not (Test-Path $Apk)) {
    Write-Host "APK 不存在: $Apk"
    exit 1
}

$sel = @()
if ($Serial) { $sel = @("-s", $Serial) }

# --- 1. 等设备就绪（unauthorized / offline 都继续等）--------------------------
$ready = $false
for ($i = 0; $i -lt $wait; $i++) {
    $st = ((& $adb @sel get-state 2>$null) -join "").Trim()
    if ($st -eq "device") { $ready = $true; break }
    Start-Sleep -Seconds 1
}

if (-not $ready) {
    $serialNote = if ($Serial) { "（指定 $Serial）" } else { "" }
    Write-Host "==> 错误：${wait}s 内没有就绪的设备$serialNote"
    # 不带 -s：列出全部设备，便于对照 serial 是否写错 / 设备是否 offline
    (& $adb devices -l 2>&1 | Out-String) -split "\r?\n" | ForEach-Object { Write-Host "    $_" }
    Write-Host "    检查：数据线 / 无线调试是否断开、手机是否解锁并已授权调试、"
    Write-Host "         开发者选项里的「USB 调试」是否还开着（HyperOS 息屏后可能自己关）"
    exit 1
}

# --- 2. 主通道：adb install ---------------------------------------------------
Write-Host "==> adb install -r $Apk$(if ($Serial) { " (设备 $Serial)" })"
$out = (& $adb @sel install -r $Apk 2>&1 | Out-String).Trim()
Write-Host $out
if ($LASTEXITCODE -eq 0) {
    Write-Host "==> 安装成功"
    exit 0
}

# --- 3. 只有被 ROM 拦截才降级 -------------------------------------------------
if ($out -notmatch "INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_VERIFICATION_FAILURE|INSTALL_FAILED_ABORTED") {
    Write-Host "==> 安装失败：不是 ROM 拦截（原因见上），不做降级"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "==> 被 HyperOS/MIUI 拦截，降级为 push + pm install（走 shell 通道绕过）"
$remote = "/data/local/tmp/" + [System.IO.Path]::GetFileName($Apk)
& $adb @sel push $Apk $remote
if ($LASTEXITCODE -ne 0) { exit 1 }
& $adb @sel shell pm install -r -t $remote
$rc = $LASTEXITCODE
& $adb @sel shell rm -f $remote > $null 2>&1

if ($rc -eq 0) {
    Write-Host "==> 安装成功（pm install 通道）"
} else {
    Write-Host "==> 安装失败（pm install 返回 $rc）"
    Write-Host "    设置 → 更多设置 → 开发者选项 → 打开「USB 调试（安全设置）」（需登录小米账号 + 插 SIM 卡）"
}
exit $rc

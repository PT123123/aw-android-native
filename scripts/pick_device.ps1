# 从 adb 设备列表里挑设备：scripts/pick_device.ps1 [-Kind phone|tab] [-WaitSecs N] [-List]
#
# 规则：型号（ro.product.model）含 pad / tablet，或以 TB 开头（联想 Tab 系列）的视为平板。
#   -Kind phone      输出第一个非平板设备的 serial（默认）
#   -Kind tab        输出第一个平板设备的 serial
#   -WaitSecs S      一台在线设备都没有时每秒重试，最多 S 秒
#   -List            列出每台在线设备及其判定结果（排查「未检测到设备」时用），总是 exit 0
#
# 型号取不到时不猜：getprop 在设备刚连上 / HyperOS 息屏后偶尔返回空，
# 此时标记为 unknown 并跳过该设备——猜错的代价是把 APK 装到另一台设备上。
# 调用方（Justfile 的 install）会重试若干秒，等 getprop 恢复正常。
param(
    [ValidateSet("phone", "tab")]
    [string]$Kind = "phone",
    [int]$WaitSecs = 0,
    [switch]$List
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Justfile 里 export 了 ADB 绝对路径；直接手动跑时退回 PATH 里的 adb
$adb = if ($env:ADB) { $env:ADB } else { "adb" }

# 枚举在线设备，输出对象列表 Serial / Model / Kind（phone|tab|unknown）
# 型号优先取 `adb devices -l` 行里的 model: 字段——一次 adb 调用就能拿到全部设备，
# 而 `adb shell getprop` 在 Windows 上每次要几百 ms~数秒，等待循环里会被放大成几十秒。
# 只有该字段缺失时才回退 getprop（老版 adb / 部分无线调试场景）。
function Get-Devices {
    $raw = (& $adb devices -l 2>$null) -join "`n"
    $result = @()
    foreach ($line in ($raw -split "\r?\n")) {
        $t = $line.Trim() -split "\s+"
        if ($t.Count -lt 2) { continue }
        $serial = $t[0]
        $state  = $t[1]
        if ($state -ne "device") { continue }
        $model = ""
        foreach ($f in $t) { if ($f -like "model:*") { $model = $f.Substring(6) } }
        if (-not $model) {
            $model = ((& $adb -s $serial shell getprop ro.product.model 2>$null) -join "").Trim()
        }
        $kind = if (-not $model) { "unknown" }
                elseif ($model -match "pad|tablet" -or $model -match "^tb") { "tab" }
                else { "phone" }
        $result += [pscustomobject]@{ Serial = $serial; Model = $model; Kind = $kind }
    }
    return , $result
}

if ($List) {
    $devs = Get-Devices
    if (-not $devs -or $devs.Count -eq 0) {
        Write-Host "    （adb 里没有已授权的在线设备）"
        (& $adb devices -l 2>&1 | Out-String) -split "\r?\n" | ForEach-Object { Write-Host "    $_" }
    } else {
        Write-Host ("    {0,-20} {1,-16} {2}" -f "SERIAL", "MODEL", "判定")
        foreach ($d in $devs) {
            Write-Host ("    {0,-20} {1,-16} {2}" -f $d.Serial, $d.Model, $d.Kind)
        }
    }
    exit 0
}

# 选择循环：
#   目标在线          -> 立即输出 serial，exit 0
#   有其他设备在线    -> 立即失败（adb 通道正常，就是目标设备没插，不值得干等）
#   一台设备都没有    -> 每秒重试，最多 WaitSecs 秒（WaitSecs<=0 表示只试一次）
$waited = 0
while ($true) {
    $devs = Get-Devices
    $hit = $devs | Where-Object { $_.Kind -eq $Kind } | Select-Object -First 1
    if ($hit) { Write-Output $hit.Serial; exit 0 }
    if ($devs -and $devs.Count -gt 0) { exit 1 }
    if ($WaitSecs -le 0 -or $waited -ge $WaitSecs) { exit 1 }
    $waited++
    Start-Sleep -Seconds 1
}

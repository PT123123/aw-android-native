#!/usr/bin/env bash
# 安装 APK 到 adb 设备。
#
# 主通道就是 adb install -r（流式安装）。只有 MIUI/HyperOS 明确拦截安装通道
# （Failure [INSTALL_FAILED_USER_RESTRICTED]）时，才降级为 adb push + pm install，
# 后者走 shell 通道可以绕过该限制（HyperOS / Android 15 真机实测）。
#
# 其他失败（no devices / offline / unauthorized / 别的 INSTALL_FAILED_*）一律不降级，
# 直接报真实原因——旧版对所有失败都盲目降级，会把「设备掉线」误报成「被 ROM 拦截」，
# 然后再失败一次，日志完全误导。
#
# HyperOS 息屏 / USB 挂起时 adb 会短暂「no devices」，手动重跑就好；这里先轮询等就绪。
#
# Usage: bash scripts/adb_install.sh <apk> [serial]
#   serial 省略时由 adb 自行选择（仅连一台设备时够用）
#   ADB_WAIT_SECS 可覆盖等待就绪的秒数（默认 20）
set -u

APK="${1:?用法: bash scripts/adb_install.sh <apk> [serial]}"
SERIAL="${2:-}"

# Justfile 里 export 了 ADB 绝对路径；直接手动跑时退回 PATH 里的 adb
ADB="${ADB:-adb}"
WAIT="${ADB_WAIT_SECS:-20}"

if [ ! -f "$APK" ]; then
  echo "APK 不存在: $APK" >&2
  exit 1
fi

SEL=()
[ -n "$SERIAL" ] && SEL=(-s "$SERIAL")

adb_() { "$ADB" ${SEL[@]+"${SEL[@]}"} "$@"; }

# --- 1. 等设备就绪（unauthorized / offline 都继续等）--------------------------
ready=0
for _ in $(seq 1 "$WAIT"); do
  if [ "$(adb_ get-state 2>/dev/null)" = "device" ]; then
    ready=1
    break
  fi
  sleep 1
done

if [ "$ready" -eq 0 ]; then
  echo "==> 错误：${WAIT}s 内没有就绪的设备${SERIAL:+（指定 $SERIAL）}" >&2
  # 不带 -s：列出全部设备，便于对照 serial 是否写错 / 设备是否 offline
  "$ADB" devices -l >&2
  echo "    检查：数据线 / 无线调试是否断开、手机是否解锁并已授权调试、" >&2
  echo "         开发者选项里的「USB 调试」是否还开着（HyperOS 息屏后可能自己关）" >&2
  exit 1
fi

# --- 2. 主通道：adb install ---------------------------------------------------
echo "==> adb install -r $APK${SERIAL:+ (设备 $SERIAL)}"
log=$(mktemp)
adb_ install -r "$APK" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
out=$(cat "$log")
rm -f "$log"

if [ "$rc" -eq 0 ]; then
  echo "==> 安装成功"
  exit 0
fi

# --- 3. 只有被 ROM 拦截才降级 -------------------------------------------------
case "$out" in
  *INSTALL_FAILED_USER_RESTRICTED* | *INSTALL_FAILED_VERIFICATION_FAILURE* | *INSTALL_FAILED_ABORTED*)
    ;;
  *)
    echo "==> 安装失败：不是 ROM 拦截（原因见上），不做降级" >&2
    exit "$rc"
    ;;
esac

echo ""
echo "==> 被 HyperOS/MIUI 拦截，降级为 push + pm install（走 shell 通道绕过）"
REMOTE="/data/local/tmp/$(basename "$APK")"
adb_ push "$APK" "$REMOTE" || exit 1
adb_ shell pm install -r -t "$REMOTE"
rc=$?
adb_ shell rm -f "$REMOTE" >/dev/null 2>&1

if [ "$rc" -eq 0 ]; then
  echo "==> 安装成功（pm install 通道）"
else
  echo "==> 安装失败（pm install 返回 $rc）" >&2
  echo "    设置 → 更多设置 → 开发者选项 → 打开「USB 调试（安全设置）」（需登录小米账号 + 插 SIM 卡）" >&2
fi
exit "$rc"

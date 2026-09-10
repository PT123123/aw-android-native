#!/usr/bin/env bash
# 安装 APK 到 adb 设备，并自动绕过 HyperOS / MIUI 的 adb 流式安装限制。
#
# 背景：HyperOS(V816+)/MIUI 在「USB 调试（安全设置）」未开启时，会拦截
# `adb install` 使用的 install-session 通道，直接返回：
#   Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
# 而且设备上不弹任何确认框。但同一个包用 `adb push` + `pm install` 走 shell
# 通道可以正常装上（HyperOS / Android 15 真机实测）。
# 因此这里先试 adb install，失败则自动降级，避免手动折腾。
#
# Usage: bash scripts/adb_install.sh <apk> [serial]
#   serial 省略时由 adb 自行选择（仅连一台设备时够用）
set -u

APK="${1:?用法: bash scripts/adb_install.sh <apk> [serial]}"
SERIAL="${2:-}"

# Justfile 里 export 了 ADB 绝对路径；直接手动跑时退回 PATH 里的 adb
ADB="${ADB:-adb}"

if [ ! -f "$APK" ]; then
  echo "APK 不存在: $APK" >&2
  exit 1
fi

SEL=()
[ -n "$SERIAL" ] && SEL=(-s "$SERIAL")

echo "==> adb install -r $APK${SERIAL:+ (设备 $SERIAL)}"
if "$ADB" ${SEL[@]+"${SEL[@]}"} install -r "$APK"; then
  echo "==> 安装成功（adb install 通道）"
  exit 0
fi

echo ""
echo "==> adb install 被拦截，降级为 push + pm install（绕过 MIUI/HyperOS 安装限制）"
REMOTE="/data/local/tmp/$(basename "$APK")"
"$ADB" ${SEL[@]+"${SEL[@]}"} push "$APK" "$REMOTE" || exit 1
"$ADB" ${SEL[@]+"${SEL[@]}"} shell pm install -r -t "$REMOTE"
rc=$?
"$ADB" ${SEL[@]+"${SEL[@]}"} shell rm -f "$REMOTE" >/dev/null 2>&1

if [ $rc -eq 0 ]; then
  echo "==> 安装成功（pm install 通道）"
else
  echo "==> 安装失败（pm install 返回 $rc）" >&2
  echo "    若仍是 USER_RESTRICTED：设置 → 更多设置 → 开发者选项 →" >&2
  echo "    打开「USB 调试（安全设置）」（需登录小米账号 + 插 SIM 卡）" >&2
fi
exit $rc

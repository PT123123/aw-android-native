#!/usr/bin/env bash
# 从 adb 设备列表里挑设备：scripts/pick_device.sh phone|tab|--wait|--list
#
# 规则：型号（ro.product.model）含 pad / tablet，或以 TB 开头（联想 Tab 系列）的视为平板。
#   phone        -> 输出第一个非平板设备的 serial
#   tab          -> 输出第一个平板设备的 serial
#   --wait K [S] -> 同上，但在线设备为空时每秒重试，最多 S 秒（默认 10）
#   --list       -> 列出每台在线设备及其判定结果（排查「未检测到设备」时用），总是 exit 0
#
# 型号取不到时**不猜**：getprop 在设备刚连上 / HyperOS 息屏后偶尔返回空，
# 此时标记为 unknown 并跳过该设备——猜错的代价是把 APK 装到另一台设备上。
# 调用方（Justfile 的 install）会重试若干秒，等 getprop 恢复正常。
set -u
ADB="${ADB:-adb}"

# 枚举在线设备，逐行输出 "serial<TAB>model<TAB>phone|tab|unknown"
#
# 型号优先取 `adb devices -l` 行里的 model: 字段——一次 adb 调用就能拿到全部设备，
# 而 `adb shell getprop` 在 Windows 上每次要几百 ms~数秒，等待循环里会被放大成几十秒。
# 只有该字段缺失时才回退 getprop（老版 adb / 部分无线调试场景）。
devices() {
  "$ADB" devices -l 2>/dev/null | tr -d '\r' | while read -r line; do
    # shellcheck disable=SC2086
    set -- $line
    s="${1:-}"
    st="${2:-}"
    [ -n "$s" ] && [ "$st" = "device" ] || continue
    m=""
    for f in "$@"; do
      case "$f" in model:*) m="${f#model:}" ;; esac
    done
    if [ -z "$m" ]; then
      m=$("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    fi
    if [ -z "$m" ]; then
      printf '%s\t%s\t%s\n' "$s" "?" "unknown"
    elif echo "$m" | grep -qiE 'pad|tablet|^tb'; then
      printf '%s\t%s\t%s\n' "$s" "$m" "tab"
    else
      printf '%s\t%s\t%s\n' "$s" "$m" "phone"
    fi
  done
}

case "${1:-phone}" in
  --list|-l)
    out=$(devices)
    if [ -z "$out" ]; then
      echo "    （adb 里没有已授权的在线设备）"
      "$ADB" devices -l 2>&1 | sed 's/^/    /'
    else
      printf '    %-20s %-16s %s\n' "SERIAL" "MODEL" "判定"
      printf '%s\n' "$out" | while IFS=$'\t' read -r s m t; do
        printf '    %-20s %-16s %s\n' "$s" "$m" "$t"
      done
    fi
    exit 0
    ;;
  --wait|-w)
    # --wait <phone|tab> [秒数]：等待目标设备就绪，成功时输出 serial。
    #   目标在线            -> 立即返回；
    #   有其他设备在线      -> 立即失败（adb 通道正常，就是目标设备没插，不值得干等）
    #   一台在线设备都没有  -> 每秒重试，最多 [秒数]（默认 10）秒
    # 放在脚本内循环而不是让 Justfile 反复调本脚本：Windows 上起一个 bash 进程要 ~1.3s，
    # 每轮都重启进程的话 10 轮要 20s 以上，脚本内每轮只花一次 adb 调用（~0.6s）。
    kind="${2:-phone}"
    secs="${3:-10}"
    case "$kind" in
      phone|tab) ;;
      *) echo "用法: bash scripts/pick_device.sh --wait phone|tab [秒数]" >&2; exit 2 ;;
    esac
    waited=0
    while :; do
      out=$(devices)
      hit=$(printf '%s\n' "$out" | while IFS=$'\t' read -r s _m t; do
              if [ "$t" = "$kind" ]; then echo "$s"; break; fi
            done)
      if [ -n "$hit" ]; then echo "$hit"; exit 0; fi
      [ -n "$out" ] && exit 1
      waited=$((waited + 1))
      [ "$waited" -ge "$secs" ] && exit 1
      sleep 1
    done
    ;;
  phone|tab) ;;
  *)
    echo "用法: bash scripts/pick_device.sh [phone|tab|--list|--wait phone|tab [秒数]]" >&2
    exit 2
    ;;
esac

kind="${1:-phone}"
# 用进程替换而非管道：管道里的 exit 只退子 shell，会继续遍历并多打印一行
while IFS=$'\t' read -r s _m t; do
  if [ "$t" = "$kind" ]; then
    echo "$s"
    exit 0
  fi
done < <(devices)

# 没找到目标设备
exit 1

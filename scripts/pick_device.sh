#!/usr/bin/env bash
# 从 adb 设备列表里按类型挑设备：scripts/pick_device.sh phone|tab
# 规则：型号含 pad / tablet，或以 TB 开头（联想 Tab 系列）的视为平板
#   phone -> 返回第一个非平板设备
#   tab   -> 返回第一个命中平板特征的设备
ADB="${ADB:-adb}"
kind="${1:-phone}"
for s in $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'); do
  m=$("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  if echo "$m" | grep -qiE 'pad|tablet|^tb'; then
    if [ "$kind" = "tab" ]; then echo "$s"; exit 0; fi
  else
    if [ "$kind" = "phone" ]; then echo "$s"; exit 0; fi
  fi
done
# 没找到目标设备
exit 1

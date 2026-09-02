#!/usr/bin/env bash
# 从 adb 设备列表里挑出"手机"：排除型号像平板的设备
# 规则：型号含 pad / tablet，或以 TB 开头（联想 Tab 系列）的视为平板
ADB="${ADB:-adb}"
for s in $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'); do
  m=$("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  if ! echo "$m" | grep -qiE 'pad|tablet|^tb'; then
    echo "$s"
    exit 0
  fi
done
# 没找到手机
exit 1

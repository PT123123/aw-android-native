#!/usr/bin/env python3
"""
将 PNG 图片转换为 Android 应用图标（各密度 mipmap PNG）。

用法:
    python scripts/png_to_icon.py <输入.png> [输出目录]

示例:
    python scripts/png_to_icon.py my_icon.png
    python scripts/png_to_icon.py my_icon.png mobile/src/main/res

默认输出到 mobile/src/main/res，生成 aw_launcher / aw_launcher_round / aw_launcher_foreground。
"""

import sys
import os
from PIL import Image

# Android 图标密度对应像素尺寸
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# adaptive icon 需要的额外尺寸（drawable 目录，108dp + 安全边距）
ADAPTIVE_SIZE = 108  # dp，实际像素按 xxxhdpi 192 等比


def resize_icon(input_path: str, output_dir: str):
    """读取 PNG 并生成各密度图标。"""
    src = Image.open(input_path).convert("RGBA")

    # 确保输入是正方形，不是则居中裁剪
    w, h = src.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        src = src.crop((left, top, left + side, top + side))
        print(f"  [WARN] Non-square input, cropped to {side}x{side}")

    base = os.path.dirname(os.path.abspath(__file__))
    res_dir = output_dir or os.path.join(base, "..", "mobile", "src", "main", "res")

    for density, px in DENSITIES.items():
        resized = src.resize((px, px), Image.LANCZOS)
        out_dir = os.path.join(res_dir, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        # aw_launcher.png — 完整图标（旧设备）
        resized.save(os.path.join(out_dir, "aw_launcher.png"))
        # aw_launcher_round.png — 圆形版本（同内容）
        resized.save(os.path.join(out_dir, "aw_launcher_round.png"))
        # aw_launcher_foreground.png — adaptive icon 前景层
        resized.save(os.path.join(out_dir, "aw_launcher_foreground.png"))
        print(f"  [OK] mipmap-{density}/aw_launcher*.png ({px}x{px})")

    # 同时生成 adaptive icon 背景色文件（从图片主色调提取或白色）
    update_background_color(res_dir)

    print(f"\nDone! Icons written to {res_dir}/mipmap-*/")
    print("Rebuild to apply: ./gradlew :mobile:assembleDebug")


def update_background_color(res_dir: str):
    """更新 values/aw_launcher_background.xml 为白色（透明图标需要白底）。"""
    values_dir = os.path.join(res_dir, "values")
    os.makedirs(values_dir, exist_ok=True)
    color_file = os.path.join(values_dir, "aw_launcher_background.xml")

    content = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="aw_launcher_background">#FFFFFF</color>
</resources>
"""
    with open(color_file, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  [OK] values/aw_launcher_background.xml (white bg)")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        print("错误：请提供输入 PNG 文件路径")
        sys.exit(1)

    input_path = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else None

    if not os.path.isfile(input_path):
        print(f"错误：文件不存在: {input_path}")
        sys.exit(1)

    if not input_path.lower().endswith(".png"):
        print("警告：输入文件不是 PNG 格式，尝试继续...")

    resize_icon(input_path, output_dir)


if __name__ == "__main__":
    main()

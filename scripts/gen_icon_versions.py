#!/usr/bin/env python3
"""
从一张 PNG 生成多个版本号的图标（01, 02, 03 ...）。
每个版本复制为独立的 mipmap 资源，供 Android 端切换。

用法:
    python scripts/gen_icon_versions.py <输入.png> [起始版本号] [数量]

示例:
    python scripts/gen_icon_versions.py icon.png          # 生成 version_01
    python scripts/gen_icon_versions.py icon.png 1 5      # 生成 version_01 到 version_05
    python scripts/gen_icon_versions.py icon.png 3 1      # 只生成 version_03
"""

import sys
import os
import shutil
from PIL import Image

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def generate_versions(input_path: str, start: int = 1, count: int = 1):
    """生成 version_XX 系列的图标目录。"""
    src = Image.open(input_path).convert("RGBA")

    # 确保正方形
    w, h = src.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        src = src.crop((left, top, left + side, top + side))
        print(f"  [WARN] Non-square input, cropped to {side}x{side}")

    base = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.join(base, "..", "mobile", "src", "main", "res")

    generated = []
    for i in range(count):
        version_num = start + i
        version_name = f"version_{version_num:02d}"
        generated.append(version_name)

        for density, px in DENSITIES.items():
            resized = src.resize((px, px), Image.LANCZOS)
            out_dir = os.path.join(res_dir, f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)

            # 版本化文件名
            resized.save(os.path.join(out_dir, f"aw_launcher_{version_name}.png"))
            resized.save(os.path.join(out_dir, f"aw_launcher_round_{version_name}.png"))
            resized.save(os.path.join(out_dir, f"aw_launcher_foreground_{version_name}.png"))

        print(f"  [OK] {version_name} (all densities)")

    # 生成 activity-alias XML 片段
    alias_xml = generate_alias_xml(generated)
    xml_path = os.path.join(res_dir, "xml", "icon_aliases.xml")
    os.makedirs(os.path.dirname(xml_path), exist_ok=True)
    with open(xml_path, "w", encoding="utf-8") as f:
        f.write(alias_xml)
    print(f"  [OK] xml/icon_aliases.xml ({len(generated)} aliases)")

    print(f"\nDone! {len(generated)} icon version(s) generated.")
    print("Rebuild to apply: ./gradlew :mobile:assembleDebug")


def generate_alias_xml(versions: list) -> str:
    """生成 activity-alias XML 片段（供开发者手动加入 AndroidManifest.xml）。"""
    lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<!-- 图标版本 activity-alias（由 scripts/gen_icon_versions.py 生成）-->",
        "<!-- 将此内容复制到 AndroidManifest.xml 的 <application> 标签内 -->",
        "<!-- 默认只启用一个 alias，其余 disabled=true -->",
        "",
    ]
    for v in versions:
        lines.append(f'    <activity-alias')
        lines.append(f'        android:name=".IconAlias_{v}"')
        lines.append(f'        android:targetActivity=".MainActivity"')
        lines.append(f'        android:enabled="false"')
        lines.append(f'        android:icon="@mipmap/aw_launcher_{v}"')
        lines.append(f'        android:roundIcon="@mipmap/aw_launcher_round_{v}"')
        lines.append(f'        android:label="@string/app_name"')
        lines.append(f'        android:exported="true">')
        lines.append(f'        <intent-filter>')
        lines.append(f'            <action android:name="android.intent.action.MAIN" />')
        lines.append(f'            <category android:name="android.intent.category.LAUNCHER" />')
        lines.append(f'        </intent-filter>')
        lines.append(f'    </activity-alias>')
        lines.append("")

    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        print("错误：请提供输入 PNG 文件路径")
        sys.exit(1)

    input_path = sys.argv[1]
    start = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 1

    if not os.path.isfile(input_path):
        print(f"错误：文件不存在: {input_path}")
        sys.exit(1)

    generate_versions(input_path, start, count)


if __name__ == "__main__":
    main()

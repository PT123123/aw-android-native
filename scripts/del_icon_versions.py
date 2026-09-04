#!/usr/bin/env python3
"""
删除指定版本号的图标文件。

用法:
    python scripts\del_icon_versions.py <版本号...>

示例:
    python scripts\del_icon_versions.py 01              # 删除 version_01
    python scripts\del_icon_versions.py 01 02 03        # 删除多个版本
    python scripts\del_icon_versions.py --all           # 删除所有版本图标
"""

import sys
import os
import glob

DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
FILES = ["aw_launcher", "aw_launcher_round", "aw_launcher_foreground"]


def delete_version(res_dir: str, version: int):
    """删除指定版本的图标文件。"""
    version_name = f"version_{version:02d}"
    deleted = 0

    for density in DENSITIES:
        out_dir = os.path.join(res_dir, f"mipmap-{density}")
        if not os.path.isdir(out_dir):
            continue
        for f in FILES:
            pattern = os.path.join(out_dir, f"{f}_{version_name}.png")
            if os.path.isfile(pattern):
                os.remove(pattern)
                deleted += 1

    if deleted > 0:
        print(f"  [DEL] {version_name} ({deleted} files removed)")
    else:
        print(f"  [SKIP] {version_name} (not found)")
    return deleted


def delete_all_versions(res_dir: str):
    """删除所有版本化图标文件。"""
    deleted = 0
    for density in DENSITIES:
        out_dir = os.path.join(res_dir, f"mipmap-{density}")
        if not os.path.isdir(out_dir):
            continue
        for f in FILES:
            pattern = os.path.join(out_dir, f"{f}_version_*.png")
            for filepath in glob.glob(pattern):
                os.remove(filepath)
                deleted += 1

    if deleted > 0:
        print(f"  [DEL] All version icons ({deleted} files removed)")
    else:
        print(f"  [SKIP] No version icons found")
    return deleted


def main():
    base = os.path.dirname(os.path.abspath(__file__))
    res_dir = os.path.join(base, "..", "mobile", "src", "main", "res")

    if len(sys.argv) < 2:
        print(__doc__)
        print("错误：请提供版本号或使用 --all")
        sys.exit(1)

    if sys.argv[1] == "--all":
        delete_all_versions(res_dir)
    else:
        for arg in sys.argv[1:]:
            try:
                version = int(arg)
                delete_version(res_dir, version)
            except ValueError:
                print(f"  [SKIP] Invalid version: {arg}")

    print("\nDone!")


if __name__ == "__main__":
    main()

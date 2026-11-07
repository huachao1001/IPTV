#!/usr/bin/env python3
"""把台标统一成一致的视觉尺寸。

原始素材长宽比、四周留白、内容填充率差异极大（23%~100%），
直接 fitCenter 撑满，会让有的台标顶格、有的缩成一小条。

只按外接框缩放仍然不够：宽扁台标（269x53）和方形台标（113x113）
外接框一样大，视觉重量却差好几倍。所以这里按**可见内容面积**归一化 ——
裁掉透明边后，把内容像素面积缩放到统一目标面积，再夹住长边避免溢出。
这样每个台标的视觉重量基本一致。
"""
from __future__ import annotations

import argparse
import math
import shutil
from pathlib import Path

from PIL import Image

# 目标画布：宽高比 2:1，与列表里图标的显示框一致
CANVAS_W = 480
CANVAS_H = 240
# 归一化目标面积占画布的比例
TARGET_AREA_RATIO = 0.42
# 内容长边最多占画布的多少，防止极端宽扁/细高的台标顶出边界
MAX_EDGE_RATIO = 0.98
# 低于这个 alpha 视为透明背景
ALPHA_FLOOR = 24


def trim_alpha(img: Image.Image) -> Image.Image:
    """按 alpha 通道裁掉四周透明像素。"""
    alpha = img.getchannel("A")
    box = alpha.point(lambda v: 255 if v > ALPHA_FLOOR else 0).getbbox()
    return img.crop(box) if box else img


def scale_to_area(cw: int, ch: int) -> tuple[int, int]:
    """等比缩放到目标面积，并夹住长边。"""
    target = CANVAS_W * CANVAS_H * TARGET_AREA_RATIO
    scale = math.sqrt(target / max(1, cw * ch))
    new_w = max(1, round(cw * scale))
    new_h = max(1, round(ch * scale))

    max_w = CANVAS_W * MAX_EDGE_RATIO
    max_h = CANVAS_H * MAX_EDGE_RATIO
    shrink = min(1.0, max_w / new_w, max_h / new_h)
    if shrink < 1.0:
        new_w = max(1, round(new_w * shrink))
        new_h = max(1, round(new_h * shrink))
    return new_w, new_h


def normalize(path: Path, backup: Path) -> tuple[str, int, int, int, int]:
    """返回 (文件名, 原宽, 原高, 内容宽, 内容高)。"""
    with Image.open(path) as raw:
        img = raw.convert("RGBA")
    ow, oh = img.size
    content = trim_alpha(img)
    cw, ch = content.size
    if cw < 2 or ch < 2:
        return path.name, ow, oh, cw, ch

    new_w, new_h = scale_to_area(cw, ch)
    content = content.resize((new_w, new_h), Image.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    canvas.paste(content, ((CANVAS_W - new_w) // 2, (CANVAS_H - new_h) // 2))

    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup / path.name)
    canvas.save(path, "PNG", optimize=True)
    return path.name, ow, oh, cw, ch


def main() -> None:
    ap = argparse.ArgumentParser(description="台标视觉尺寸归一化")
    ap.add_argument("logos", nargs="?", default="app/src/main/assets/logos",
                    help="台标目录，默认 app/src/main/assets/logos")
    ap.add_argument("--backup", default=None,
                    help="原图备份目录，默认 tools/logo_originals")
    ap.add_argument("--restore", action="store_true",
                    help="从备份还原，不做处理")
    args = ap.parse_args()

    logo_dir = Path(args.logos)
    if not logo_dir.is_dir():
        raise SystemExit(f"目录不存在：{logo_dir}")
    backup_dir = Path(args.backup) if args.backup else Path("tools/logo_originals")

    if args.restore:
        n = 0
        for src in sorted(backup_dir.glob("*.png")):
            shutil.copy2(src, logo_dir / src.name)
            n += 1
        print(f"已还原 {n} 个台标")
        return

    # 备份目录必须是原图：已存在就还原，避免把处理过的图当原图再备份一遍
    if backup_dir.is_dir() and any(backup_dir.glob("*.png")):
        for src in sorted(backup_dir.glob("*.png")):
            shutil.copy2(src, logo_dir / src.name)

    changed = 0
    for path in sorted(logo_dir.glob("*.png")):
        name, ow, oh, cw, ch = normalize(path, backup_dir)
        print(f"{name:<24} {ow:>4}x{oh:<4} 内容 {cw:>4}x{ch:<4}")
        changed += 1

    print(f"\n已处理 {changed} 个台标 → 画布 {CANVAS_W}x{CANVAS_H}，"
          f"目标内容面积 {int(TARGET_AREA_RATIO * 100)}%")
    print(f"原图备份在 {backup_dir}，可用 --restore 还原")


if __name__ == "__main__":
    main()

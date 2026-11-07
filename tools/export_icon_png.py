#!/usr/bin/env python3
"""把图标导出成 PNG 图片（不依赖 APK，直接用 Chrome 无头渲染）。

用法：python tools/export_icon_png.py [输出目录]
默认输出到 build/icons/
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOL = HERE / "make_launcher_icon.py"

spec = importlib.util.spec_from_file_location("launcher", TOOL)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")


def mark() -> str:
    parts = []
    for d, c in launcher.FILLS:
        parts.append(f'<path d="{d}" fill="{c}"/>')
    for d, c, w in launcher.STROKES:
        parts.append(
            f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w:g}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>'
        )
    return "".join(parts)


def svg(scale: float, size: int, bg: str | None, round_bg: bool = False) -> str:
    tx = 54 - launcher.BOX_CX * scale
    ty = 54 - launcher.BOX_CY * scale
    content = f'<g transform="translate({tx:.2f},{ty:.2f}) scale({scale:g})">{mark()}</g>'
    if round_bg:
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">'
            f'<defs><clipPath id="c"><circle cx="54" cy="54" r="54"/></clipPath></defs>'
            f'<g clip-path="url(#c)">'
            f'<rect width="108" height="108" fill="{bg}"/>{content}</g></svg>'
        )
    under = f'<rect width="108" height="108" fill="{bg}"/>' if bg else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 108 108">{under}{content}</svg>'
    )


def render(name: str, svg_text: str, size: int, out: Path, transparent: bool = False):
    html = out / f"_{name}.html"
    html.write_text(
        f'<!doctype html><html><body style="margin:0">{svg_text}</body></html>',
        encoding="utf-8",
    )
    png = out / f"{name}.png"
    cmd = [
        str(CHROME), "--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--virtual-time-budget=3000",
        f"--screenshot={png}", f"--window-size={size},{size}",
        f"file:///{html.as_posix()}",
    ]
    if transparent:
        cmd.insert(3, "--default-background-color=00000000")
    subprocess.run(cmd, check=True, capture_output=True)
    html.unlink(missing_ok=True)
    return png


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd() / "build" / "icons"
    out.mkdir(parents=True, exist_ok=True)

    sq = launcher.SQUARE_SCALE
    rd = launcher.ROUND_SCALE
    fg = launcher.FOREGROUND_SCALE
    targets = [
        # 名称, 缩放, 底色, 尺寸, 圆形遮罩, 透明底
        ("icon_512_square", sq, launcher.BG, 512, False, False),
        ("icon_512_round", rd, launcher.BG, 512, True, False),
        ("icon_256_foreground", fg, None, 256, False, True),
        ("icon_128_square", sq, launcher.BG, 128, False, False),
        ("icon_48_square", sq, launcher.BG, 48, False, False),
        ("icon_round_48", rd, launcher.BG, 48, True, False),
    ]
    for name, scale, bg, size, rnd, clear in targets:
        p = render(name, svg(scale, size, bg, round_bg=rnd), size, out, transparent=clear)
        print(p)


if __name__ == "__main__":
    main()

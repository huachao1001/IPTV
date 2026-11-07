#!/usr/bin/env python3
"""把定稿图标导出成一张评审图（PNG）。

用法：python tools/make_launcher_icon.py  先出资源
      python tools/export_icon_preview.py [输出目录]
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

MARK = []


def mark_svg() -> str:
    parts = []
    for d, c in launcher.FILLS:
        parts.append(f'<path d="{d}" fill="{c}"/>')
    for d, c, w in launcher.STROKES:
        parts.append(
            f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w:g}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>'
        )
    return "".join(parts)


def icon_svg(scale: float, size: int, bg: str | None = None) -> str:
    tx = 54 - launcher.BOX_CX * scale
    ty = 54 - launcher.BOX_CY * scale
    under = f'<rect width="108" height="108" fill="{bg}"/>' if bg else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 108 108">{under}<g transform="translate({tx:.2f},{ty:.2f}) '
        f'scale({scale:g})">{mark_svg()}</g></svg>'
    )


def build_html() -> str:
    big = icon_svg(launcher.SQUARE_SCALE, 260, launcher.BG)
    round_big = icon_svg(launcher.ROUND_SCALE, 260, launcher.BG)
    tile_scale = 0.82
    tiles = "".join(
        f'<div class="tile{" first" if i == 0 else ""}">'
        f'<div class="ico">{icon_svg(tile_scale, 96, launcher.BG)}</div>'
        f'<div class="lbl">IPTV 直播</div></div>'
        for i in range(5)
    )
    sizes = "".join(
        f'<div class="sz"><div class="box">{icon_svg(tile_scale, s, launcher.BG)}</div>'
        f'<div class="px">{s}px</div></div>'
        for s in (128, 96, 64, 48, 32)
    )
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
*{{box-sizing:border-box}}
body{{margin:0;background:#1B2129;font-family:'Microsoft YaHei','Segoe UI',sans-serif;color:#B8C2D4}}
.wrap{{display:flex;flex-direction:column;gap:1px}}
.sec{{background:#0E1218;padding:20px 24px}}
.title{{font-size:12px;color:#6C7A8C;letter-spacing:.08em;margin-bottom:16px}}
.main{{display:flex;gap:24px;align-items:center}}
.square{{width:260px;height:260px;overflow:hidden;flex:0 0 auto}}
.round{{width:260px;height:260px;overflow:hidden;border-radius:50%;flex:0 0 auto}}
.square svg,.round svg{{display:block}}
.note{{flex:1;min-width:0;font-size:13px;line-height:2.0;color:#93A1B4}}
.note b{{color:#E6EDF7;font-weight:600}}
.row{{display:flex;gap:8px}}
.tile{{width:144px;padding:14px 0 12px;background:#0E1218;border-radius:12px;
text-align:center;border:2px solid transparent}}
.tile.first{{border-color:#E9EDF4;background:#151B24}}
.ico{{width:96px;height:96px;margin:0 auto 10px;overflow:hidden;border-radius:20px}}
.ico svg{{display:block}}
.lbl{{font-size:13px;color:#C6D0DE}}
.sizes{{display:flex;gap:28px;align-items:flex-end}}
.sz{{text-align:center}}
.box{{background:#0E1218;border-radius:14px;padding:6px}}
.box svg{{display:block}}
.px{{font-size:11px;color:#6C7A8C;margin-top:7px}}
</style></head><body><div class="wrap">

<div class="sec">
  <div class="title">图标 · 天线电视</div>
  <div class="main">
    <div class="square">{big}</div>
    <div class="round">{round_big}</div>
    <div class="note">
      <b>机身 104 × 64 单位</b>（最初 68 × 31），几乎顶满画布，电视存在感最强。<br>
      <b>顶部 V 形天线</b>是两根带球头的拉杆，压在机顶中心。<br>
      <b>屏幕内白波</b>是直播流本身，缩到 32px 也还能认出。<br>
      配色沿用 App 品牌色：机身与天线 #3D8BFD、波形 #FFFFFF、底色 #0B1018。
    </div>
  </div>
</div>

<div class="sec">
  <div class="title">电视桌面效果（第一格为选中态）</div>
  <div class="row">{tiles}</div>
</div>

<div class="sec">
  <div class="title">实际尺寸</div>
  <div class="sizes">{sizes}</div>
</div>

</div></body></html>"""


def main():
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd() / "build"
    out_dir.mkdir(parents=True, exist_ok=True)
    html = out_dir / "icon_preview.html"
    html.write_text(build_html(), encoding="utf-8")

    png = out_dir / "icon_preview.png"
    chrome = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
    subprocess.run(
        [
            str(chrome), "--headless=new", "--disable-gpu",
            "--virtual-time-budget=4000", "--hide-scrollbars",
            f"--screenshot={png}", "--window-size=760,880", f"file:///{html.as_posix()}",
        ],
        check=True,
        capture_output=True,
    )
    print(png)


if __name__ == "__main__":
    main()

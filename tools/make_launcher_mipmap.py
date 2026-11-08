#!/usr/bin/env python3
"""把天线电视图标渲染成各密度 PNG，供老 launcher 使用。

为什么必须有 PNG
----------------
API 26+ 走 `mipmap-anydpi-v26` 的自适应图标，没问题。
但本项目 minSdk 是 21，而实测设备是 **小米电视 Android 6.0.1（API 23）**，
桌面是 `com.mitv.tvhome`（2016 年的老 launcher）。

这类老 launcher 加载图标时会把 Drawable **强转成 BitmapDrawable**
再塞进自己的图标缓存：

    BitmapDrawable bd = (BitmapDrawable) activityInfo.loadIcon(pm);

`VectorDrawable` 不是 `BitmapDrawable`，强转抛 ClassCastException，
launcher 捕获后**退回它缓存里的旧图标** —— 这就是"换了图标但桌面没变"。

所以 API 21~25 必须提供**真实 PNG 位图**，并且不能留 `mipmap-anydpi/*.xml`：
`anydpi` 这个密度限定符的优先级高于具体密度桶，留着矢量它就会一直被选中，
PNG 永远轮不到。

用法
----
    python tools/make_launcher_mipmap.py

输出
----
    res/mipmap-mdpi/ic_launcher.png         48px
    res/mipmap-hdpi/ic_launcher.png         72px
    res/mipmap-xhdpi/ic_launcher.png        96px
    res/mipmap-xxhdpi/ic_launcher.png      144px
    res/mipmap-xxxhdpi/ic_launcher.png     192px
    ... 每个密度同时输出 ic_launcher_round.png（圆形，圆外透明）
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE.parent / "app" / "src" / "main" / "res"
TOOL = HERE / "make_launcher_icon.py"

spec = importlib.util.spec_from_file_location("launcher", TOOL)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)

CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")

# 各密度对应的启动图标边长。
#
# 这里按**两倍**标准尺寸出图（标准 48dp，小米电视桌面实际画 96dp）：
# 实测 1920x1080 / density 320 的小米电视桌面，图标格子是 192px，
# 而 320dpi 走 xhdpi 桶。若 xhdpi 只给标准 96px，就会被放大 2 倍显示 —— 这正是
# 「图标看着模糊」的原因。每个桶都给到两倍像素，launcher 就是 1:1 取用。
DENSITIES = {
    "mdpi": 96,
    "hdpi": 144,
    "xhdpi": 192,
    "xxhdpi": 288,
    "xxxhdpi": 384,
}

# 先按大尺寸渲染再降采样，边缘比直接渲染小图干净
RENDER_AT = 1024


def mark() -> str:
    """图标图形本体（矩形/圆/贝塞尔都在 make_launcher_icon 里定义）。"""
    parts = []
    for d, c in launcher.FILLS:
        parts.append(f'<path d="{d}" fill="{c}"/>')
    for d, c, w in launcher.STROKES:
        parts.append(
            f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w:g}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>'
        )
    return "".join(parts)


def svg(scale: float, round_icon: bool) -> str:
    tx = 54 - launcher.BOX_CX * scale
    ty = 54 - launcher.BOX_CY * scale
    body = f'<g transform="translate({tx:.2f},{ty:.2f}) scale({scale:g})">{mark()}</g>'
    # 宽高必须等于渲染尺寸：只给 viewBox 加 108 的宽高的话，
    # Chrome 会按 108 CSS 像素画在左上角，截出来的 512 图有 3/4 是空白。
    size = RENDER_AT
    if round_icon:
        # 圆外必须透明：老 launcher 不做遮罩，会直接看到方角
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            'viewBox="0 0 108 108">'
            '<defs><clipPath id="c"><circle cx="54" cy="54" r="54"/></clipPath></defs>'
            f'<g clip-path="url(#c)">'
            f'<rect width="108" height="108" fill="{launcher.BG}"/>{body}</g></svg>'
        )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        'viewBox="0 0 108 108">'
        f'<rect width="108" height="108" fill="{launcher.BG}"/>{body}</svg>'
    )


def render_svg(svg_text: str, out_png: Path, w: int = None, h: int = None) -> None:
    html = out_png.with_suffix(".html")
    html.write_text(
        '<!doctype html><html><body style="margin:0">' + svg_text + "</body></html>",
        encoding="utf-8",
    )
    w = w or RENDER_AT
    h = h or RENDER_AT
    subprocess.run(
        [
            str(CHROME), "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--default-background-color=00000000",
            "--virtual-time-budget=3000",
            f"--screenshot={out_png}",
            f"--window-size={w},{h}",
            f"file:///{html.as_posix()}",
        ],
        check=True, capture_output=True,
    )
    html.unlink(missing_ok=True)


# ---------------- banner（桌面横向大图） ----------------
# 电视桌面（leanback）在应用行里显示的是 banner 而不是 icon。
# 旧 banner.xml 还是"播放三角"那版设计，且是矢量图——
# 只要桌面走 banner 通道，换图标就完全看不出变化。
BANNER_DP = (320, 180)
BANNER_RENDER_AT = (1280, 720)
# 内容占 banner 高度的比例：太满显得顶格，太小又看不清
BANNER_FILL = 0.62


def banner_svg() -> tuple[str, int, int]:
    """把天线电视放到 320x180 的中央，返回 (svg, 渲染宽, 渲染高)。"""
    box_w = launcher.BOX[2] - launcher.BOX[0]
    box_h = launcher.BOX[3] - launcher.BOX[1]
    bdp_w, bdp_h = BANNER_DP
    scale = (bdp_h * BANNER_FILL) / max(1e-6, box_h)
    # 内容太宽就按宽度收紧，避免左右出血
    max_w = bdp_w * 0.52
    if box_w * scale > max_w:
        scale = max_w / box_w

    # 把 BOX 中心对到画布中心
    box_cx = (launcher.BOX[0] + launcher.BOX[2]) / 2
    box_cy = (launcher.BOX[1] + launcher.BOX[3]) / 2
    tx = bdp_w / 2 - box_cx * scale
    ty = bdp_h / 2 - box_cy * scale

    rw, rh = BANNER_RENDER_AT
    svg_text = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{rw}" height="{rh}" '
        f'viewBox="0 0 {bdp_w} {bdp_h}">'
        f'<rect width="{bdp_w}" height="{bdp_h}" fill="{launcher.BG}"/>'
        f'<g transform="translate({tx:.2f},{ty:.2f}) scale({scale:.4f})">'
        f'{mark()}</g></svg>'
    )
    return svg_text, rw, rh


def main() -> None:
    from PIL import Image

    if not CHROME.exists():
        raise SystemExit(f"找不到 Chrome：{CHROME}")

    tmp = RES / "_icon_tmp"
    tmp.mkdir(parents=True, exist_ok=True)

    jobs = [
        ("square", launcher.SQUARE_SCALE, False),
        ("round", launcher.ROUND_SCALE, True),
    ]
    rendered: dict[str, Path] = {}
    for name, scale, is_round in jobs:
        p = tmp / f"{name}.png"
        render_svg(svg(scale, is_round), p)
        rendered[name] = p

    made = 0
    for dens, size in DENSITIES.items():
        d = RES / f"mipmap-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        for name, suffix in (("square", ""), ("round", "_round")):
            with Image.open(rendered[name]) as im:
                im = im.convert("RGBA").resize((size, size), Image.LANCZOS)
                im.save(d / f"ic_launcher{suffix}.png", "PNG", optimize=True)
            made += 1

    # 清掉会遮蔽 PNG 的矢量 anydpi 资源
    stale_dir = RES / "mipmap-anydpi"
    if stale_dir.is_dir():
        for f in stale_dir.glob("*.xml"):
            f.unlink()
        try:
            stale_dir.rmdir()
        except OSError:
            pass

    # banner：电视桌面（leanback）显示的是它，不是 icon。
    # 旧 banner.xml 是"播放三角"设计且为矢量图，必须一起换掉。
    bsvg, bw, bh = banner_svg()
    bpng = tmp / "banner.png"
    render_svg(bsvg, bpng, bw, bh)
    banner_dir = RES / "drawable-nodpi"
    banner_dir.mkdir(parents=True, exist_ok=True)
    with Image.open(bpng) as im:
        im.convert("RGB").resize((1280, 720), Image.LANCZOS).save(
            banner_dir / "banner.png", "PNG", optimize=True
        )
    for stale in (RES / "drawable").glob("banner.*"):
        if stale.suffix != ".png":
            stale.unlink()

    made += 1

    for f in tmp.glob("*"):
        f.unlink(missing_ok=True)
    tmp.rmdir()

    print(f"生成 {made} 个 PNG（{len(DENSITIES)} 个密度 × 方形/圆形）")
    print("已移除 mipmap-anydpi/*.xml（否则会遮蔽 PNG）")


if __name__ == "__main__":
    main()

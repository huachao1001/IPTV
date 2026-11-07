#!/usr/bin/env python3
"""生成桌面图标资源（全部为 VectorDrawable，几何一致）。

造型：大电视（拉宽到 84 单位）+ 顶部 V 形天线 + 屏幕内一条直播波形。
配色沿用 App 内品牌色：机身 #3D8BFD / 天线 #3D8BFD / 屏内波形 #FFFFFF / 底 #0B1018。

产出：
  res/drawable/ic_launcher_foreground.xml   自适应图标前景层
  res/mipmap-anydpi/ic_launcher.xml         API 21~25 入口
  res/mipmap-anydpi/ic_launcher_round.xml   API 21~25 圆形入口
  res/mipmap-anydpi-v26/ic_launcher.xml     API 26+ 自适应图标
  res/mipmap-anydpi-v26/ic_launcher_round.xml

改造型后重跑一次即可：python tools/make_launcher_icon.py
"""
from __future__ import annotations

from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

BG = "#0B1018"
ACCENT = "#3D8BFD"
LIGHT = "#8CC2FF"
WHITE = "#FFFFFF"


# ---------- path 工具 ----------
def rr(x, y, w, h, r):
    """圆角矩形（单条闭合子路径，顺时针）。"""
    return (
        f"M{x + r:g},{y:g}H{x + w - r:g}A{r:g},{r:g} 0 0 1 {x + w:g},{y + r:g}"
        f"V{y + h - r:g}A{r:g},{r:g} 0 0 1 {x + w - r:g},{y + h:g}"
        f"H{x + r:g}A{r:g},{r:g} 0 0 1 {x:g},{y + h - r:g}"
        f"V{y + r:g}A{r:g},{r:g} 0 0 1 {x + r:g},{y:g}Z"
    )


def line(x0, y0, x1, y1):
    return f"M{x0:g},{y0:g}L{x1:g},{y1:g}"


def wave(y, amp, x0, x1, cycles):
    """正弦近似：交替二次贝塞尔，控制点距离 2*amp，实际峰值 ≈ amp。"""
    n = max(2, int(round(cycles * 2)))
    step = (x1 - x0) / n
    d = [f"M{x0:g},{y:g}"]
    sign = -1
    for i in range(n):
        sx = x0 + i * step
        d.append(f"Q{sx + step / 2:.2f},{y + sign * amp * 2:.2f} {sx + step:.2f},{y:g}")
        sign *= -1
    return "".join(d)


# ---------- 造型几何（108 单位画布）----------
# 电视：横向拉宽到 84 单位（原来 68），高度 34
TV_X, TV_Y, TV_W, TV_H, TV_R = 2, 36, 104, 64, 14.5
BEZEL = 5.5
TV_CX = TV_X + TV_W / 2
TV_CY = TV_Y + TV_H / 2

# 天线：从机顶中心伸出两根 V 形杆，末端圆头
ANT_DX, ANT_DY, ANT_W = 19, 21, 5.2
ANT_BASE_Y = TV_Y + 2
ANT_TIP_R = 3.4

FILLS = [
    (rr(TV_X, TV_Y, TV_W, TV_H, TV_R), ACCENT),                       # 机身
    (rr(TV_X + BEZEL, TV_Y + BEZEL, TV_W - 2 * BEZEL, TV_H - 2 * BEZEL, 5.6), BG),  # 屏幕
    (rr(TV_CX - 4, TV_Y + TV_H + 1.5, 8, 5, 2.5), ACCENT),             # 支架颈
    (rr(TV_CX - 15, TV_Y + TV_H + 6.5, 30, 5, 2.5), ACCENT),           # 底座
    (f"M{TV_CX - ANT_DX - ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}"
     f"A{ANT_TIP_R:g},{ANT_TIP_R:g} 0 1 0 {TV_CX - ANT_DX + ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}"
     f"A{ANT_TIP_R:g},{ANT_TIP_R:g} 0 1 0 {TV_CX - ANT_DX - ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}Z",
     ACCENT),                                                          # 左天线球头
    (f"M{TV_CX + ANT_DX - ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}"
     f"A{ANT_TIP_R:g},{ANT_TIP_R:g} 0 1 0 {TV_CX + ANT_DX + ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}"
     f"A{ANT_TIP_R:g},{ANT_TIP_R:g} 0 1 0 {TV_CX + ANT_DX - ANT_TIP_R:g},{ANT_BASE_Y - ANT_DY:g}Z",
     ACCENT),                                                          # 右天线球头
]

STROKES = [
    (line(TV_CX - 1.8, ANT_BASE_Y, TV_CX - ANT_DX, ANT_BASE_Y - ANT_DY), ACCENT, ANT_W),
    (line(TV_CX + 1.8, ANT_BASE_Y, TV_CX + ANT_DX, ANT_BASE_Y - ANT_DY), ACCENT, ANT_W),
    (wave(TV_CY, 5.6, TV_X + 12, TV_X + TV_W - 12, 1.5), WHITE, 6.6),  # 屏内直播波形
]

# 造型外接框（含描边半宽），用于把视觉重心对到画布中心
PAD = max(ANT_W, 4.8) / 2
BOX = (TV_X - PAD, ANT_BASE_Y - ANT_DY - ANT_TIP_R - PAD,
       TV_X + TV_W + PAD, TV_Y + TV_H + 10 + PAD)
BOX_CX = (BOX[0] + BOX[2]) / 2
BOX_CY = (BOX[1] + BOX[3]) / 2


# ---------- 输出 ----------
def vector(scale: float, background: str | None, comment: str) -> str:
    """background: 底色的 pathData；None 表示透明前景层。"""
    tx = 54 - BOX_CX * scale
    ty = 54 - BOX_CY * scale
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- {comment} -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    if background:
        lines.append(f'    <path android:fillColor="{BG}" android:pathData="{background}" />')
    lines += [
        '    <group',
        f'        android:scaleX="{scale:g}"',
        f'        android:scaleY="{scale:g}"',
        f'        android:translateX="{tx:.2f}"',
        f'        android:translateY="{ty:.2f}">',
    ]
    for d, c in FILLS:
        lines += [
            '        <path',
            f'            android:fillColor="{c}"',
            f'            android:pathData="{d}" />',
        ]
    for d, c, w in STROKES:
        lines += [
            '        <path',
            f'            android:pathData="{d}"',
            f'            android:strokeColor="{c}"',
            f'            android:strokeWidth="{w:g}"',
            '            android:strokeLineCap="round"',
            '            android:strokeLineJoin="round" />',
        ]
    lines += ['    </group>', '</vector>', '']
    return "\n".join(lines)


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Android 8.0+ 自适应图标：前景是天线电视，背景为纯深色 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""

SQUARE = "M0,0h108v108h-108z"

# 三个场景的缩放：方形可满版；圆形要把四角收进 r=54；自适应前景要收进 66dp 安全区
SQUARE_SCALE = 1.0
ROUND_SCALE = 0.88
FOREGROUND_SCALE = 0.56


def main():
    square = vector(SQUARE_SCALE, SQUARE, "桌面图标：天线电视（满版方形）")
    foreground = vector(FOREGROUND_SCALE, None, "自适应图标前景：造型收在 66dp 安全区内")

    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(foreground, encoding="utf-8")

    (RES / "mipmap-anydpi").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    stale = RES / "drawable" / "ic_launcher.xml"
    if stale.exists():
        stale.unlink()
    # 圆形走自适应（系统会裁圆），旧版本按满版方形显示
    (RES / "mipmap-anydpi" / "ic_launcher.xml").write_text(square, encoding="utf-8")
    round_ = vector(ROUND_SCALE, "M54,0A54,54 0 1 0 54,108A54,54 0 1 0 54,0Z", "圆形图标：造型收进圆内")
    (RES / "mipmap-anydpi" / "ic_launcher_round.xml").write_text(round_, encoding="utf-8")
    (RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(ADAPTIVE, encoding="utf-8")
    (RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(ADAPTIVE, encoding="utf-8")

    colors = RES / "values" / "colors.xml"
    text = colors.read_text(encoding="utf-8")
    if "ic_launcher_background" not in text:
        text = text.replace(
            "    <!-- 状态色 -->",
            '    <!-- 桌面图标底色（与 bg_deep 同色系，自适应图标背景层复用） -->\n'
            '    <color name="ic_launcher_background">#0B1018</color>\n\n'
            "    <!-- 状态色 -->",
        )
        colors.write_text(text, encoding="utf-8")

    print("icons ->", RES)


if __name__ == "__main__":
    main()

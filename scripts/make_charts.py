# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Printable A4 test charts for the macro land tests (docs/test/macro-stacking-test-plan.md).
Print at 100 % (actual size) and check each sheet's 100 mm bar first.
  A  平行對焦靶   flat target facing the camera: dead-leaves texture, Siemens star, mm scales (T3, T4, T6, T12)
  B  斜放景深尺   lay at an angle to the camera: texture band with a mm ladder, 0 at the middle (T7, T10, T9)
  C  距離尺       three strips to cut and join, 0-72 cm, for lens-to-target distance (T3, T4)
  D  灰階與解析度 uniform patches (T16 noise) and line pairs 0.5-4 lp/mm (ADR-0011 check 3)
  E1, E2 (chart_stairs.py) staircase paper models (T12); F-I (chart_tools.py) distance cards, dot grid,
colour patches, screen overlay. Writes one PDF per chart, one with all, and a zip with print-kit-README.txt.
Usage: python scripts/make_charts.py [--out build/charts] [--dpi 400]
"""
import argparse
import os
import sys

# scripts/ is on the path when this file runs as a script.
from chart_parts import A4_W, Circle, PdfPages, Rectangle, dead_leaves, line_pairs, mm_scale, page, plt, siemens_star
from chart_stairs import chart_e1, chart_e2
from chart_tools import chart_f, chart_g, chart_h, chart_i, write_kit


def chart_a(dpi):
    fig, ax = page(
        "A 平行對焦靶",
        "貼在平面上、正對鏡頭（紙面與手機背面平行），鏡頭對準中央十字。用於 T3／T4 細掃、T6 畫面寬（四邊 mm 刻度）、"
        "T12 實拍。\n中央星形與周圍「枯葉」紋理在任何距離都有細節；量距離時量到紙面（中央十字）。",
    )
    size, x0, y0 = 170.0, 20.0, 50.0
    ax.imshow(dead_leaves(size, size, dpi, seed=1), cmap="gray", vmin=0, vmax=1,
              extent=(x0, x0 + size, y0, y0 + size), interpolation="nearest")
    cx, cy = x0 + size / 2, y0 + size / 2
    siemens_star(ax, cx, cy, 22)
    for r in (40, 60, 80):
        ax.add_patch(Circle((cx, cy), r, fill=False, lw=0.3, color="white", ls="--"))
    ax.plot([cx - 30, cx + 30], [cy, cy], color="red", lw=0.5)
    ax.plot([cx, cx], [cy - 30, cy + 30], color="red", lw=0.5)
    # Distance from the centre in mm along each side: read the frame width straight from a photo.
    for side in (-1, 1):
        mm_scale(ax, x0, cy + side * (size / 2), size, True, label_from=-int(size / 2), outward=side)
        mm_scale(ax, cx + side * (size / 2), y0, size, False, label_from=-int(size / 2), outward=side)
    return fig


def chart_b(dpi):
    fig, ax = page(
        "B 斜放景深尺",
        "平放在桌上，手機從斜上方約 45° 看向中央「0」線，尺的方向由近往遠（T7 景深、T10 對焦呼吸、T9 包圍重驗）。\n"
        "告訴 Claude 鏡頭到「0」線的距離；中間紋理帶等寬、處處有細節，橫線每 10 mm 一條。",
    )
    length, band, cx = 230.0, 44.0, A4_W / 2
    y0 = 30.0
    ax.imshow(dead_leaves(band, length, dpi, seed=2), cmap="gray", vmin=0, vmax=1,
              extent=(cx - band / 2, cx + band / 2, y0, y0 + length), interpolation="nearest")
    half = int(length / 2)
    # A line every 10 mm, on the labelled marks (multiples of 10 from the middle).
    for mm in range(0, int(length) + 1):
        if (mm - half) % 10 == 0:
            ax.plot([cx - band / 2 - 14, cx + band / 2 + 14], [y0 + mm, y0 + mm], color="black", lw=0.35)
    mid = y0 + length / 2
    ax.plot([cx - band / 2 - 22, cx + band / 2 + 22], [mid, mid], color="red", lw=1.0)
    ax.text(cx + band / 2 + 24, mid, "0（量距離到這條線）", fontsize=7, color="red", va="center")
    mm_scale(ax, cx - band / 2 - 14, y0, length, False, label_from=-half, outward=-1)
    mm_scale(ax, cx + band / 2 + 14, y0, length, False, label_from=-half, outward=1)
    ax.text(cx, y0 - 5, "近（靠手機）", fontsize=7, ha="center")
    ax.text(cx, y0 + length + 4, "遠", fontsize=7, ha="center")
    return fig


def chart_c():
    fig, ax = page(
        "C 距離尺（剪下三條接成 0–72 cm）",
        "沿虛線剪下。第 2、3 條下端的灰色黏貼區塞到前一條末端下面，讓這條的起點線對齊前一條的終點線，再用膠帶固定。\n"
        "「0」端貼齊手機鏡頭玻璃，直線拉到靶面，讀靶面所在的刻度。量 T3／T4 校正點用；長線每公分、中線每 5 mm。",
    )
    strip_w, length, tab, gap, x0, y0 = 34.0, 240.0, 8.0, 14.0, 22.0, 30.0
    for k in range(3):
        x = x0 + k * (strip_w + gap)
        bottom = y0 - (tab if k > 0 else 0)
        ax.add_patch(Rectangle((x, bottom), strip_w, length + (y0 - bottom), fill=False, lw=0.5, ls="--"))
        if k > 0:
            ax.add_patch(Rectangle((x, y0 - tab), strip_w, tab, color="0.85", lw=0))
            ax.text(x + strip_w / 2, y0 - tab / 2, "黏貼區", fontsize=5, ha="center", va="center")
        start_cm = 24 * k
        for mm in range(0, int(length) + 1):
            h = 12.0 if mm % 10 == 0 else (7.0 if mm % 5 == 0 else 4.0)
            ax.plot([x, x + h], [y0 + mm, y0 + mm], color="black", lw=0.3)
            if mm % 10 == 0:
                ax.text(x + h + 4, y0 + mm, f"{start_cm + mm // 10}", fontsize=7, va="center")
        if k == 0:
            ax.text(x + strip_w / 2, y0 - 5, "0 ← 鏡頭玻璃", fontsize=7, ha="center", color="red")
        ax.text(x + strip_w - 3, y0 + length / 2, f"第 {k + 1} 條", fontsize=7, rotation=90, va="center")
    return fig


def chart_d():
    fig, ax = page(
        "D 灰階與解析度",
        "上：均勻色塊，T16 以兩顆鏡頭把同一塊拍成一樣大，比較雜訊（印表機的灰不是標準灰卡，只作相對比較）。\n"
        "下：線對 0.5–4 lp/mm（ADR-0011 各倍率的清晰度、T12 實拍）。正對鏡頭放平。",
    )
    for i, (grey, name) in enumerate(((1.0, "白（紙）"), (0.55, "中灰"), (0.2, "深灰"))):
        x = 18 + i * 60
        ax.add_patch(Rectangle((x, 190), 54, 54, color=str(grey), lw=0))
        ax.add_patch(Rectangle((x, 190), 54, 54, fill=False, lw=0.3))
        ax.text(x + 27, 186, name, fontsize=7, ha="center")
    groups = (0.5, 0.71, 1.0, 1.41, 2.0, 2.83, 4.0)
    for i, lp in enumerate(groups):
        x = 18 + i * 25
        line_pairs(ax, x, 140, lp, vertical=True)
        line_pairs(ax, x, 118, lp, vertical=False)
        ax.text(x + 5, 113, f"{lp:g}", fontsize=6.5, ha="center")
    ax.text(18, 108, "lp/mm（每毫米線對數）", fontsize=6.5)
    siemens_star(ax, 105, 62, 32, spokes=72)
    ax.plot([105 - 38, 105 + 38], [62, 62], color="red", lw=0.4)
    ax.plot([105, 105], [62 - 38, 62 + 38], color="red", lw=0.4)
    return fig


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default=os.path.join("build", "charts"))
    parser.add_argument("--dpi", type=int, default=400, help="resolution of the texture images")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    charts = {
        "A-flat-focus": lambda: chart_a(args.dpi),
        "B-slanted-depth": lambda: chart_b(args.dpi),
        "C-distance-strips": chart_c,
        "D-grey-resolution": chart_d,
        "E1-staircase-5mm": lambda: chart_e1(args.dpi),
        "E2-staircase-3mm": lambda: chart_e2(args.dpi),
        "F-distance-cards": chart_f,
        "G-dot-grid": chart_g,
        "H-colour-patches": chart_h,
        "I-screen-safe-zone": chart_i,
    }
    with PdfPages(os.path.join(args.out, "anomalops-charts-all.pdf")) as everything:
        for name, make in charts.items():
            fig = make()
            fig.savefig(os.path.join(args.out, f"{name}.pdf"))
            everything.savefig(fig)
            plt.close(fig)
            print(f"wrote {name}.pdf")
    print(f"done: {write_kit(args.out, [*charts, 'anomalops-charts-all'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

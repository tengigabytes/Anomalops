# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Charts E-I of scripts/make_charts.py: tools to cut, fold or overlay rather than targets to photograph flat,
and the zip of the whole print kit."""
import os
import zipfile

from chart_parts import Circle, Rectangle, dead_leaves, mm_scale, page

FOLD = dict(color="black", lw=0.5, ls=(0, (4, 2)))
CUT = dict(color="black", lw=0.4, ls=(0, (1, 1.5)))


def grid(mm):
    """A 1 mm grid line, heavier every 5 mm."""
    return dict(color="black", lw=0.35) if mm % 5 == 0 else dict(color="0.3", lw=0.15)


def staircase(ax, x, y, width, tread, riser, steps, dpi, seed):
    """A strip that folds into [steps] treads of [tread] mm, each [riser] mm below the last; numbered 1 (top)."""
    length = steps * tread + (steps - 1) * riser
    ax.add_patch(Rectangle((x, y), width, length, fill=False, **CUT))
    top = y + length
    for k in range(steps):
        t0 = top - k * (tread + riser) - tread
        ax.imshow(dead_leaves(width - 10, tread - 2, dpi, seed=seed + k), cmap="gray", vmin=0, vmax=1,
                  extent=(x + 8, x + width - 2, t0 + 1, t0 + tread - 1), interpolation="nearest")
        ax.text(x + 4, t0 + tread / 2, str(k + 1), fontsize=9, fontweight="bold", ha="center", va="center")
        if k < steps - 1:
            r0 = t0 - riser
            ax.add_patch(Rectangle((x, r0), width, riser, color="0.9", lw=0))
            ax.plot([x, x + width], [t0, t0], **FOLD)
            ax.plot([x, x + width], [r0, r0], **FOLD)
            # Step 1 is highest and nearest: each tread's far edge folds down (mountain), the riser's foot up (valley).
            ax.text(x + width + 2, t0, "山", fontsize=5, va="center")
            ax.text(x + width + 2, r0, "谷", fontsize=5, va="center")
    return length


def chart_e(dpi):
    fig, ax = page(
        "E 階梯景深靶（剪下摺成階梯）",
        "印在厚紙上，沿點線剪下，在虛線依「山／谷」摺成階梯，背面以膠帶或紙盒撐住。鏡頭從斜上方看向階梯，第 1 階最近。\n"
        "左：每階低 5 mm（主鏡頭、望遠）；右：每階低 3 mm（超廣角近距）。已知每階深度，焦點包圍與合成可對答案（T12）。",
    )
    staircase(ax, 25, 40, 70, tread=18, riser=5, steps=8, dpi=dpi, seed=40)
    staircase(ax, 120, 80, 60, tread=12, riser=3, steps=10, dpi=dpi, seed=60)
    return fig


def chart_f():
    fig, ax = page(
        "F 距離卡（剪下對摺加硬）",
        "印在厚紙上，沿點線剪下，沿中線對摺（兩面貼合更硬）。量近距離時，一端抵住鏡頭玻璃旁的機身，另一端碰到靶面，\n"
        "調好後拿開再拍。長度就是鏡頭到靶面的距離（不含鏡頭玻璃內的距離）；T3／T4 近距點、T13、T14 用。",
    )
    lengths = (20, 30, 50, 110, 150)
    x = 20.0
    for length in lengths:
        width = 24.0
        ax.add_patch(Rectangle((x, 40), width, length, fill=False, **CUT))
        ax.plot([x + width / 2, x + width / 2], [40, 40 + length], **FOLD)
        ax.text(x + width / 4, 40 + length / 2, f"{length} mm", fontsize=8, rotation=90, ha="center", va="center")
        cm = f"{length / 10:g} cm"
        ax.text(x + 3 * width / 4, 40 + length / 2, cm, fontsize=8, rotation=90, ha="center", va="center")
        ax.plot([x, x + width], [40 + length, 40 + length], color="red", lw=1.2, zorder=3)
        x += width + 10
    ax.text(20, 34, "紅線端碰靶面；另一端抵住機身", fontsize=7, color="red")
    return fig


def chart_g():
    fig, ax = page(
        "G 點陣格線",
        "平放、正對鏡頭（紙面與手機背面平行）。圓點直徑 1.2 mm、間距 5 mm；中央 50 mm 方框內加密為 2.5 mm。\n"
        "量 T10 對焦呼吸（固定距離、改對焦時點距的變化）與畫面變形，也可拿實拍驗證對齊模組量到的縮放。",
    )
    x0, y0, size = 20.0, 45.0, 170.0
    cx, cy = x0 + size / 2, y0 + size / 2
    n = int(size / 5)
    for i in range(n + 1):
        for j in range(n + 1):
            ax.add_patch(Circle((x0 + 5 * i, y0 + 5 * j), 0.6, color="black", lw=0))
    for i in range(-10, 11):
        for j in range(-10, 11):
            if i % 2 or j % 2:
                ax.add_patch(Circle((cx + 2.5 * i, cy + 2.5 * j), 0.4, color="black", lw=0))
    ax.add_patch(Rectangle((cx - 25, cy - 25), 50, 50, fill=False, lw=0.3, color="red"))
    ax.plot([cx - 40, cx + 40], [cy, cy], color="red", lw=0.4)
    ax.plot([cx, cx], [cy - 40, cy + 40], color="red", lw=0.4)
    mm_scale(ax, x0, y0 - 4, size, True, label_from=-int(size / 2), outward=-1)
    return fig


def chart_h():
    fig, ax = page(
        "H 色塊靶",
        "平放、正對鏡頭。ADR-0011 驗證 2：連續變焦跨過換鏡頭（約 1×、4.7–4.9×）時，同一色塊的顏色有沒有跳。\n"
        "印表機的顏色不準，只作前後相對比較，不能當色彩校正（校正要用商用灰卡或色卡）。",
    )
    colours = [
        "#d62728", "#ff7f0e", "#ffdd00", "#2ca02c", "#17becf", "#1f77b4",
        "#5b2c8e", "#e377c2", "#8c564b", "#c49c94", "#bcbd22", "#7f7f7f",
        "#ff9896", "#98df8a", "#aec7e8", "#ffbb78", "#000000", "#ffffff",
    ]
    for k, colour in enumerate(colours):
        col, row = k % 6, k // 6
        x, y = 20 + col * 29, 170 - row * 40
        ax.add_patch(Rectangle((x, y), 26, 34, color=colour, lw=0))
        ax.add_patch(Rectangle((x, y), 26, 34, fill=False, lw=0.3))
        ax.text(x + 13, y - 3, colour, fontsize=5, ha="center")
    ax.add_patch(Rectangle((20, 40), 171, 40, color="0.5", lw=0))
    ax.text(105.5, 60, "中性灰底（量色偏時以此為參考）", fontsize=8, ha="center", va="center", color="white")
    return fig


def chart_i():
    width, height, safe = 66.0, 147.0, 12.0
    fig, ax = page(
        "I 螢幕安全區量規（印在投影片或描圖紙）",
        f"Pixel 10 Pro 螢幕實測 {width:g} × {height:g} mm（docs/test/m3-instrumented.md 第 7 節）；"
        f"紅框為 FR-55 的 {safe:g} mm 安全區。\n"
        "裝殼後疊在螢幕上（或隔著殼對齊螢幕邊），讀殼框蓋住幾 mm，G3 定稿 FR-55。格線每 1 mm，粗線每 5 mm。",
    )
    for k, x in enumerate((25.0, 25.0 + width + 25.0)):
        y = 50.0
        for mm in range(int(width) + 1):
            ax.plot([x + mm, x + mm], [y, y + height], **grid(mm))
        for mm in range(int(height) + 1):
            ax.plot([x, x + width], [y + mm, y + mm], **grid(mm))
        ax.add_patch(Rectangle((x, y), width, height, fill=False, lw=0.8))
        inner = (width - 2 * safe, height - 2 * safe)
        ax.add_patch(Rectangle((x + safe, y + safe), *inner, fill=False, lw=0.8, color="red"))
        ax.text(x + width / 2, y - 5, "正面" if k == 0 else "備用", fontsize=7, ha="center")
    return fig


def write_kit(out, names):
    """Zips the PDFs [names] in [out] with print-kit-README.txt (UTF-8 with a BOM, for Windows Notepad)."""
    readme = os.path.join(os.path.dirname(os.path.abspath(__file__)), "print-kit-README.txt")
    path = os.path.join(out, "anomalops-print-kit.zip")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as kit:
        with open(readme, encoding="utf-8") as f:
            kit.writestr("README.txt", "﻿" + f.read())
        for name in names:
            kit.write(os.path.join(out, f"{name}.pdf"), f"{name}.pdf")
    return os.path.abspath(path)

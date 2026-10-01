# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Drawing parts for scripts/make_charts.py: A4 page in millimetres, print-scale bar, mm scales, dead-leaves
texture, Siemens star and line pairs."""
import datetime

import matplotlib
import matplotlib.font_manager

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib.backends.backend_pdf import PdfPages  # noqa: E402,F401  (re-exported to make_charts.py)
from matplotlib.patches import Circle, Rectangle, Wedge  # noqa: E402

A4_W, A4_H = 210.0, 297.0
MM_PER_INCH = 25.4
CJK_FONTS = ["Microsoft JhengHei", "Noto Sans CJK TC", "Noto Sans TC", "PingFang TC", "DejaVu Sans"]
_available = {f.name for f in matplotlib.font_manager.fontManager.ttflist}
plt.rcParams["font.family"] = [f for f in CJK_FONTS if f in _available] or ["DejaVu Sans"]
plt.rcParams["pdf.fonttype"] = 42


def page(title, subtitle):
    fig = plt.figure(figsize=(A4_W / MM_PER_INCH, A4_H / MM_PER_INCH))
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, A4_W)
    ax.set_ylim(0, A4_H)
    ax.set_aspect("equal")
    ax.axis("off")
    ax.text(12, A4_H - 14, title, fontsize=15, fontweight="bold", va="center")
    ax.text(12, A4_H - 21, subtitle, fontsize=7.5, va="center")
    scale_bar(ax, 12, 12)
    stamp = f"Anomalops 測試靶 · {datetime.date.today().isoformat()} · scripts/make_charts.py · 以 100%（實際大小）列印"
    ax.text(A4_W - 12, 8, stamp, fontsize=6, ha="right", va="center", color="0.3")
    return fig, ax


def scale_bar(ax, x, y):
    """A 100 mm bar with 1 mm ticks: measure it after printing; it must be 100 mm."""
    ax.add_patch(Rectangle((x, y), 100, 1.2, color="black", lw=0))
    for mm in range(0, 101):
        h = 3.0 if mm % 10 == 0 else (2.0 if mm % 5 == 0 else 1.2)
        ax.plot([x + mm, x + mm], [y + 1.2, y + 1.2 + h], color="black", lw=0.25)
    ax.text(x + 103, y + 1.5, "100 mm：列印後用尺量，必須剛好 100 mm", fontsize=6.5, va="center")


def dead_leaves(width_mm, height_mm, dpi, seed, r_min_mm=0.15, r_max_mm=6.0):
    """Occluding disks with a 1/r^3 size law and random grey: texture at every scale, for sharpness measures."""
    rng = np.random.default_rng(seed)
    px = dpi / MM_PER_INCH
    w, h = int(width_mm * px), int(height_mm * px)
    image = np.full((h, w), np.nan, dtype=np.float32)
    r_min, r_max = r_min_mm * px, r_max_mm * px
    remaining = w * h
    while remaining > 0.002 * w * h:
        u = rng.random()
        r = 1.0 / np.sqrt((1 - u) / r_min**2 + u / r_max**2)  # inverse CDF of p(r) ∝ r^-3
        cx, cy = rng.random() * w, rng.random() * h
        grey = 0.08 + 0.84 * rng.random()
        x0, x1 = max(int(cx - r), 0), min(int(cx + r) + 1, w)
        y0, y1 = max(int(cy - r), 0), min(int(cy + r) + 1, h)
        if x0 >= x1 or y0 >= y1:
            continue
        yy, xx = np.ogrid[y0:y1, x0:x1]
        disk = (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r
        window = image[y0:y1, x0:x1]
        fresh = disk & np.isnan(window)
        n = int(fresh.sum())
        if n:
            window[fresh] = grey
            remaining -= n
    image[np.isnan(image)] = 0.5
    return image


def siemens_star(ax, cx, cy, radius, spokes=36):
    ax.add_patch(Circle((cx, cy), radius, color="white", lw=0))
    step = 360.0 / spokes
    for k in range(spokes // 2):
        ax.add_patch(Wedge((cx, cy), radius, 2 * k * step, (2 * k + 1) * step, color="black", lw=0))
    ax.add_patch(Circle((cx, cy), radius, fill=False, lw=0.4, color="black"))


def mm_scale(ax, x0, y0, length, horizontal, label_from=0, label_step=10, outward=1):
    """mm ticks along a line; labels every [label_step] mm, numbered from [label_from]."""
    for mm in range(0, int(length) + 1):
        h = 3.0 if mm % 10 == 0 else (2.0 if mm % 5 == 0 else 1.2)
        if horizontal:
            ax.plot([x0 + mm, x0 + mm], [y0, y0 + outward * h], color="black", lw=0.25)
        else:
            ax.plot([x0, x0 + outward * h], [y0 + mm, y0 + mm], color="black", lw=0.25)
        if (label_from + mm) % label_step == 0:
            text = str(label_from + mm)
            if horizontal:
                ax.text(x0 + mm, y0 + outward * 5, text, fontsize=5, ha="center", va="center")
            else:
                ax.text(x0 + outward * 5.5, y0 + mm, text, fontsize=5, ha="center", va="center")


def line_pairs(ax, x, y, lp_per_mm, vertical, size=10.0):
    """Five bar pairs at [lp_per_mm] line pairs per mm in a [size] mm square."""
    bar = 1.0 / (2 * lp_per_mm)
    n = min(5, int(size / (2 * bar)))
    for i in range(n):
        if vertical:
            ax.add_patch(Rectangle((x + 2 * i * bar, y), bar, size, color="black", lw=0))
        else:
            ax.add_patch(Rectangle((x, y + 2 * i * bar), size, bar, color="black", lw=0))

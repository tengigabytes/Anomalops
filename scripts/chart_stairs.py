# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Chart E of scripts/make_charts.py: a paper-model net that folds into a staircase block with known step depths.

The block's side profile: a front wall the full height, then treads stepping down by one riser each away from the
front, then a short back wall; the bottom stays open. One strip (front wall, treads, risers, back wall) carries
glue tabs on both long edges and wraps around two side panels cut to the profile. Seen from straight above, each
tread is one riser farther from the camera than the one before: a focus bracket's ground truth for T12.
"""
from matplotlib.patches import Polygon, Rectangle

from chart_parts import dead_leaves, page

FOLD = dict(color="black", lw=0.5, ls=(0, (4, 2)))
CUT = dict(color="black", lw=0.5)
TAB = 6.0


def segments(steps, tread, riser):
    """The strip from the front wall's foot to the back wall's foot: (kind, length, label)."""
    out = [("front", steps * riser, "正面")]
    for k in range(steps):
        out.append(("tread", tread, str(k + 1)))
        if k < steps - 1:
            out.append(("riser", riser, ""))
    out.append(("back", riser, "背"))
    return out


def strip(ax, x, top, width, steps, tread, riser, dpi, seed):
    """The strip, top to bottom, with tabs; folds marked 山 (outward corner) and 谷 (inward corner)."""
    y = top
    parts = segments(steps, tread, riser)
    for i, (kind, length, label) in enumerate(parts):
        y0 = y - length
        ax.add_patch(Rectangle((x, y0), width, length, fill=False, lw=0.3, color="0.6"))
        if kind == "tread":
            ax.imshow(dead_leaves(width - 12, length - 2, dpi, seed=seed + i), cmap="gray", vmin=0, vmax=1,
                      extent=(x + 10, x + width - 2, y0 + 1, y - 1), interpolation="nearest")
            ax.text(x + 5, y0 + length / 2, label, fontsize=9, fontweight="bold", ha="center", va="center")
        elif label:
            ax.text(x + width / 2, y0 + length / 2, label, fontsize=8, ha="center", va="center")
        for side in (-1, 1):
            edge = x if side < 0 else x + width
            inset = min(1.0, length / 3)
            ax.add_patch(Polygon([(edge, y0), (edge + side * TAB, y0 + inset), (edge + side * TAB, y - inset),
                                  (edge, y)], closed=True, fill=False, **CUT))
        if i < len(parts) - 1:
            # Front→tread and tread→riser turn outward (mountain); riser→tread turns inward (valley).
            fold = "谷" if kind == "riser" else "山"
            ax.plot([x, x + width], [y0, y0], **FOLD)
            ax.text(x + width + TAB + 1.5, y0, fold, fontsize=5, va="center")
        y = y0
    ax.plot([x, x], [y, top], **FOLD)
    ax.plot([x + width, x + width], [y, top], **FOLD)
    ax.plot([x, x + width], [top, top], **CUT)
    ax.plot([x, x + width], [y, y], **CUT)
    return top - y


def side_panel(ax, x, top, steps, tread, riser, mirror):
    """The block's side profile, depth running down the page from [top], height across from [x]."""
    profile = [(0.0, 0.0), (0.0, steps * riser)]
    for k in range(steps):
        h = (steps - k) * riser
        profile += [(k * tread, h), ((k + 1) * tread, h)]
        if k < steps - 1:
            profile.append(((k + 1) * tread, h - riser))
    profile += [(steps * tread, riser), (steps * tread, 0.0)]
    width = steps * riser
    points = [(x + (width - h if mirror else h), top - d) for d, h in profile]
    ax.add_patch(Polygon(points, closed=True, fill=False, **CUT))
    ax.text(x + width / 2, top - steps * tread / 2, "側板" + ("（右）" if mirror else "（左）"), fontsize=7,
            rotation=90, ha="center", va="center")
    ax.text(x + (width - 2 if mirror else 2), top + 2, "正面", fontsize=5, ha="right" if mirror else "left")


def stair_chart(dpi, steps, tread, riser, width, title):
    fig, ax = page(
        title,
        "厚紙印，實線剪下（含兩側小三角黏貼片），虛線摺：「山」向外摺、「谷」向內摺，黏貼片全部向內摺。"
        "長條沿兩片側板的輪廓\n繞一圈，黏貼片貼在側板內側；底部不封。手機在正上方往下拍：第 1 階最近，"
        f"之後每階遠 {riser:g} mm（T12 景深合成可對答案）。",
    )
    top = 262.0
    strip(ax, 22.0, top, width, steps, tread, riser, dpi, seed=40 + steps)
    panel = steps * riser
    x = 22.0 + width + 2 * TAB + 14
    side_panel(ax, x, top, steps, tread, riser, mirror=False)
    side_panel(ax, x + panel + 12, top, steps, tread, riser, mirror=True)
    return fig


def chart_e1(dpi):
    return stair_chart(dpi, steps=8, tread=18.0, riser=5.0, width=60.0, title="E1 階梯景深靶紙模型（每階 5 mm）")


def chart_e2(dpi):
    return stair_chart(dpi, steps=10, tread=12.0, riser=3.0, width=50.0, title="E2 階梯景深靶紙模型（每階 3 mm）")

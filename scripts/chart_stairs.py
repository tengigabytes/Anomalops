# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Chart E of scripts/make_charts.py: a one-piece paper-model net that folds into a staircase block with known
step depths.

The block's side profile: a front wall the full height, then treads stepping down by one riser each away from the
front, then a short back wall; the bottom stays open. The strip (front wall, treads, risers, back wall) runs down
the page; the two side panels hang off tread 1's long edges, so the whole net is cut as one piece. The strip's
other long edges carry glue tabs, each kept clear of the panel beside it. Seen from straight above, each tread is
one riser farther from the camera than the one before: a focus bracket's ground truth for T12.
"""
import math

from matplotlib.patches import Polygon, Rectangle

from chart_parts import A4_W, dead_leaves, page

FOLD = dict(color="black", lw=0.5, ls=(0, (4, 2)))
CUT = dict(color="black", lw=0.5)
TAB = 6.0
MIN_TAB = 1.5


def segments(steps, tread, riser):
    """The strip from the front wall's foot to the back wall's foot: (kind, length, label)."""
    out = [("front", steps * riser, "正面")]
    for k in range(steps):
        out.append(("tread", tread, str(k + 1)))
        if k < steps - 1:
            out.append(("riser", riser, ""))
    out.append(("back", riser, "背"))
    return out


def tab_height(depth, steps, tread, riser):
    """The glue tab that fits beside a strip segment starting [depth] mm below tread 1's top fold.

    A side panel lies beside the strip from that fold down to its own back edge; at step k it stands k risers off
    the strip, and a tab reaching it shares its cut line. 0 means tread 1 itself, the panels' hinge.
    """
    if depth < 0 or depth > steps * tread:
        return TAB
    gap = math.floor((depth + 0.5) / tread) * riser
    return min(TAB, gap)


def strip(ax, x, top, width, steps, tread, riser, dpi, seed):
    """The strip, top to bottom, with tabs; folds marked 山 (outward corner) and 谷 (inward corner).

    Returns the y of tread 1's top fold, where the side panels hang.
    """
    y = top
    hinge = None
    parts = segments(steps, tread, riser)
    for i, (kind, length, label) in enumerate(parts):
        y0 = y - length
        if label == "1":
            hinge = y
        ax.add_patch(Rectangle((x, y0), width, length, fill=False, lw=0.3, color="0.6"))
        if kind == "tread":
            ax.imshow(dead_leaves(width - 12, length - 2, dpi, seed=seed + i), cmap="gray", vmin=0, vmax=1,
                      extent=(x + 10, x + width - 2, y0 + 1, y - 1), interpolation="nearest")
            ax.text(x + 5, y0 + length / 2, label, fontsize=9, fontweight="bold", ha="center", va="center")
        elif label:
            ax.text(x + width / 2, y0 + length / 2, label, fontsize=8, ha="center", va="center")
        tab = tab_height(-1 if hinge is None else hinge - y, steps, tread, riser)
        for side in (-1, 1):
            edge = x if side < 0 else x + width
            if tab >= MIN_TAB:
                inset = min(1.0, length / 3)
                ax.add_patch(Polygon([(edge, y0), (edge + side * tab, y0 + inset), (edge + side * tab, y - inset),
                                      (edge, y)], closed=True, fill=False, **CUT))
            ax.plot([edge, edge], [y0, y], **(FOLD if tab >= MIN_TAB or tab == 0 else CUT))
        if i < len(parts) - 1:
            # Front→tread and tread→riser turn outward (mountain); riser→tread turns inward (valley).
            ax.plot([x, x + width], [y0, y0], **FOLD)
            ax.text(x + 1, y0, "谷" if kind == "riser" else "山", fontsize=5, va="center")
        y = y0
    ax.plot([x, x + width], [top, top], **CUT)
    ax.plot([x, x + width], [y, y], **CUT)
    return hinge


def side_panel(ax, hinge_x, hinge_y, steps, tread, riser, side):
    """The side profile hanging off tread 1's edge at [hinge_x]: depth down the page, height falling away from
    the strip on [side] (-1 left, 1 right). The hinge itself is the strip's fold line."""
    height = steps * riser
    profile = [(0.0, 0.0), (0.0, height)]
    for k in range(steps):
        h = (steps - k) * riser
        profile += [(k * tread, h), ((k + 1) * tread, h)]
        if k < steps - 1:
            profile.append(((k + 1) * tread, h - riser))
    profile += [(steps * tread, riser), (steps * tread, 0.0)]
    # From the hinge's far end round the steps, the back, the bottom and the front back to the hinge.
    outline = profile[3:] + profile[:2]
    ax.plot([hinge_x + side * (height - h) for _, h in outline], [hinge_y - d for d, _ in outline], **CUT)
    ax.text(hinge_x + side * 2.5, hinge_y - tread / 2, "山", fontsize=6, ha="center", va="center")
    ax.text(hinge_x + side * (height + riser) / 2, hinge_y - 1.5 * tread, "側板" + ("（左）" if side < 0 else "（右）"),
            fontsize=7, ha="center", va="center")


def stair_chart(dpi, steps, tread, riser, width, title):
    fig, ax = page(
        title,
        "厚紙印，沿實線剪下整片（含小梯形黏貼片），虛線摺：「山」向外摺、「谷」向內摺，黏貼片全部向內摺。"
        "兩片側板沿第 1 階\n兩邊往下摺，長條沿側板輪廓繞下去，黏貼片貼在側板內側；底部不封。手機在正上方往下拍：第 1 階最近，"
        f"之後每階遠 {riser:g} mm（T12）。",
    )
    x = (A4_W - width) / 2
    hinge = strip(ax, x, 262.0, width, steps, tread, riser, dpi, seed=40 + steps)
    side_panel(ax, x, hinge, steps, tread, riser, side=-1)
    side_panel(ax, x + width, hinge, steps, tread, riser, side=1)
    return fig


def chart_e1(dpi):
    return stair_chart(dpi, steps=8, tread=18.0, riser=5.0, width=60.0, title="E1 階梯景深靶紙模型（每階 5 mm）")


def chart_e2(dpi):
    return stair_chart(dpi, steps=10, tread=12.0, riser=3.0, width=50.0, title="E2 階梯景深靶紙模型（每階 3 mm）")

# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Tables for T7, T10 and the T9 recheck from a SlantedRulerExperiment log (docs/test/macro-stacking-test-plan.md,
section 4). Reads logcat text (any prefix before the tag's message).

T7: every band of the image is one distance on the ruler. Over the held focus steps, each band's sharpness rises
and falls; its peak (parabola through the top three steps) is the focus that band is at, and the width above half
of the peak is that band's depth of field, compared with 2 x hyperfocal. T10: the row-profile scale between the
farthest and nearest step, per diopter. T9 recheck: each bracket frame's sharpest band, mapped back to a focus
distance through the T7 band peaks, against what the frame asked for.

Usage: python scripts/ruler_report.py <logcat.txt>
"""
import re
import sys
from collections import defaultdict

T7_HEAD = re.compile(r"T7 targetCm=([\d.]+) lens=(\w+) step=([\d.]+)(?: hyperfocal=([\d.]+))? steps=(\d+)")
T7_STEP = re.compile(r"T7 lens=(\w+) req=([\d.]+) peak=(\d+) sharp=(\d+)\.\.(\d+) bands=([\d,]+)")
T10 = re.compile(r"T10 lens=(\w+) far ([\d.]+) D -> near ([\d.]+) D: scale ([\d.]+) shift (-?[\d.]+) rows")
T9_HEAD = re.compile(r"T9b targetCm=([\d.]+) lens=(\w+) distances=([\d.,]+)")
T9_HOLD = re.compile(r"T9b lens=(\w+) hold=(\d+) peaks=\[([\d, ]*)\] reports=(.*)$")
FAIL = re.compile(r"(T7|T9b) lens=(\w+) FAIL (.*)$")


def parse(lines):
    log = {"head": {}, "steps": defaultdict(list), "t10": {}, "t9": {}, "holds": defaultdict(list), "fail": []}
    for line in lines:
        if m := T7_HEAD.search(line):
            log["head"][m[2]] = {"target_cm": float(m[1]), "hyperfocal": float(m[4]) if m[4] else None}
        elif m := T7_STEP.search(line):
            bands = [float(v) for v in m[6].split(",")]
            log["steps"][m[1]].append((float(m[2]), bands))
        elif m := T10.search(line):
            log["t10"][m[1]] = (float(m[2]), float(m[3]), float(m[4]))
        elif m := T9_HEAD.search(line):
            log["t9"][m[2]] = [float(v) for v in m[3].split(",")]
        elif m := T9_HOLD.search(line):
            peaks = [int(v) for v in m[3].replace(" ", "").split(",") if v]
            log["holds"][m[1]].append((int(m[2]), peaks, m[4].split()))
        elif m := FAIL.search(line):
            log["fail"].append(f"{m[1]} lens {m[2]}: {m[3]}")
    return log


def band_focus(steps, band):
    """The focus (diopters) where [band] is sharpest, and its width above half of the peak; None if at an end."""
    steps = sorted(steps)
    values = [bands[band] for _, bands in steps]
    best = max(range(len(values)), key=values.__getitem__)
    if best in (0, len(values) - 1):
        return None
    (d0, v0), (d1, v1), (d2, v2) = [(steps[i][0], values[i]) for i in (best - 1, best, best + 1)]
    curve = v0 - 2 * v1 + v2
    peak = d1 if curve >= 0 else d1 + (d2 - d0) / 2 * (v0 - v2) / (2 * curve)
    floor = min(values)
    half = floor + (v1 - floor) / 2
    lo = best
    while lo > 0 and values[lo - 1] >= half:
        lo -= 1
    hi = best
    while hi < len(values) - 1 and values[hi + 1] >= half:
        hi += 1
    width = None if lo == 0 or hi == len(values) - 1 else crossing(steps, values, lo, hi, half)
    return peak, width


def crossing(steps, values, lo, hi, half):
    """Width between the interpolated half-peak crossings below [lo] and above [hi]."""
    def at(i, j):
        t = (half - values[i]) / (values[j] - values[i])
        return steps[i][0] + t * (steps[j][0] - steps[i][0])
    return at(hi, hi + 1) - at(lo, lo - 1)


def interpolate(points, x):
    """Linear interpolation over sorted (x, y) points, extrapolating from the end segments."""
    if len(points) < 2:
        return None
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1 or (x1, y1) == points[-1]:
            return y0 + (x - x0) * (y1 - y0) / (x1 - x0)
    return None


def report(log, out=print):
    mappings = {}
    for lens, steps in log["steps"].items():
        head = log["head"].get(lens, {})
        h = head.get("hyperfocal")
        out(f"\n## T7 lens {lens} (target {head.get('target_cm')} cm, {len(steps)} steps)")
        out("| band | focus D | width D | width / (2H) |\n| --- | --- | --- | --- |")
        points = []
        for band in range(len(steps[0][1])):
            found = band_focus(steps, band)
            if not found:
                out(f"| {band} | out of range | | |")
                continue
            peak, width = found
            points.append((band, peak))
            ratio = f"{width / (2 * h):.2f}" if width and h else ""
            out(f"| {band} | {peak:.3f} | {width:.3f} | {ratio} |" if width else f"| {band} | {peak:.3f} | | |")
        mappings[lens] = points
        if lens in log["t10"]:
            far, near, scale = log["t10"][lens]
            out(f"\nT10 lens {lens}: scale {scale:.4f} from {far:.3f} to {near:.3f} D, "
                f"{(scale - 1) * 100 / (near - far):+.2f} % per D")
    for lens, holds in log["holds"].items():
        asked = log["t9"].get(lens, [])
        points = mappings.get(lens, [])
        edges = (0, len(log["steps"][lens][0][1]) - 1) if log["steps"].get(lens) else ()
        out(f"\n## T9 recheck lens {lens} (an edge band may be past the ruler's end)")
        out("| hold | frame | asked D | sharpest band | focus D | focus - asked |\n| --- | --- | --- | --- | --- | --- |")
        for hold, peaks, _reports in holds:
            for i, band in enumerate(peaks):
                focus = interpolate(points, band)
                want = asked[i] if i < len(asked) else None
                diff = f"{focus - want:+.3f}" if focus is not None and want is not None else ""
                shown = (f"{focus:.3f}" if focus is not None else "") + (" (edge)" if band in edges else "")
                out(f"| {hold} | {i + 1} | {want if want is not None else ''} | {band} | {shown} | {diff} |")
    for fail in log["fail"]:
        out(f"\nFAIL {fail}")


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
        report(parse(f))
    return 0


if __name__ == "__main__":
    sys.exit(main())

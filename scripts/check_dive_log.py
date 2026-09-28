# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""FR-45 acceptance check of one dive session directory (ADR-0008), pulled from the phone with adb.

Checks that session.json, sensors.csv, touches.csv and captures.csv exist, that sensors.csv timestamps rise
strictly, and that sensors.csv has at least 98% of the rows its length calls for (mvp-acceptance.md: a 90-minute
session needs >= 5292 of 5400 rows). Prints the longest gap between rows. Session data stays out of the repo.

Usage: python scripts/check_dive_log.py <session dir> [--minutes 90]   (exit code 1 on a failed check)
"""
import argparse
import csv
import json
import os
import sys

REQUIRED = ["session.json", "sensors.csv", "touches.csv", "captures.csv"]
COMPLETENESS = 0.98
NS_PER_S = 1_000_000_000


def sensor_times(path):
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f))
    if not rows or rows[0][0] != "elapsed_ns":
        raise ValueError("sensors.csv has no elapsed_ns header")
    return [int(row[0]) for row in rows[1:] if row]


def check(directory, minutes):
    errors = []
    missing = [name for name in REQUIRED if not os.path.isfile(os.path.join(directory, name))]
    errors += [f"missing {name}" for name in missing]
    if "session.json" not in missing:
        with open(os.path.join(directory, "session.json"), encoding="utf-8") as f:
            session = json.load(f)
        print(f"session {session.get('id')} started {session.get('start_utc')}")
    if "sensors.csv" in missing:
        return errors
    times = sensor_times(os.path.join(directory, "sensors.csv"))
    if not times:
        return errors + ["sensors.csv has no rows"]
    backwards = [i for i in range(1, len(times)) if times[i] <= times[i - 1]]
    if backwards:
        errors.append(f"{len(backwards)} timestamp(s) not rising, first at data row {backwards[0] + 1}")
    span_s = (times[-1] - times[0]) / NS_PER_S
    expected = minutes * 60 if minutes else int(span_s) + 1
    needed = int(expected * COMPLETENESS)
    gaps = [(times[i] - times[i - 1]) / NS_PER_S for i in range(1, len(times))]
    print(f"rows {len(times)}, span {span_s:.1f} s, expected {expected}, needed {needed}")
    print(f"longest gap {max(gaps, default=0.0):.2f} s")
    if len(times) < needed:
        errors.append(f"rows {len(times)} < needed {needed} ({COMPLETENESS:.0%} of {expected})")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("directory")
    parser.add_argument("--minutes", type=int, help="session length the rows are judged against (default: its span)")
    args = parser.parse_args()
    errors = check(args.directory, args.minutes)
    for e in errors:
        print(f"ERROR {e}")
    print(f"{len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

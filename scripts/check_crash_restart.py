# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""NFR-1 acceptance: repeated crash restarts in dive lock (ADR-0006, docs/test/m3-test-plan.md).

Needs the foss or play debug build on a phone that is already in dive lock (pinned, pressed by hand). Each round
injects a crash with `am start ... --ez injectCrash true`, then waits for the app's log line
"restarted after crash in N ms" and checks that the task is still pinned, the process ID changed, and no new
FR-45 session directory appeared (the session continues, ADR-0008 note). An instrumented test cannot do this:
the crash would kill the test process too.

A crash within 60 s of a restart is not restarted (maintainer decision of 2026-09-30, ADR-0006 note), so rounds are
--gap seconds apart (default 65; 10 rounds take about 11 minutes). --crash-loop checks that rule instead: one
restart, then a second crash at once, which must not restart and must end the pin (the phone may lock afterwards).

Usage: python scripts/check_crash_restart.py [--rounds 10] [--gap 65] [--crash-loop] [--serial SERIAL]
       (exit code 1 on a failed round)
"""
import argparse
import re
import subprocess
import sys
import time

PACKAGE = "io.github.tengigabytes.anomalops"
ACTIVITY = f"{PACKAGE}/.MainActivity"
DIVES = f"/sdcard/Android/data/{PACKAGE}/files/dives"
LIMIT_MS = 3000  # NFR-1: back in dive lock within 3 s
WAIT_S = 6.0
POLL_S = 0.25
SETTLE_S = 2.0
GAP_S = 65.0  # CrashLoop.WINDOW_MS is 60 s
RESTART = re.compile(r"restarted after crash in (\d+) ms")


def adb(serial, *args):
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(command, capture_output=True, text=True, check=False).stdout


def pinned(serial):
    text = adb(serial, "shell", "dumpsys", "activity", "activities")
    match = re.search(r"mLockTaskModeState=(\w+)", text)
    return match is not None and match.group(1) != "NONE"


def pid(serial):
    return adb(serial, "shell", "pidof", PACKAGE).strip()


def sessions(serial):
    return sorted(adb(serial, "shell", "ls", DIVES).split())


def restart_ms(serial):
    """Waits for the restart log line; None when it does not come within WAIT_S."""
    deadline = time.monotonic() + WAIT_S
    while time.monotonic() < deadline:
        match = RESTART.search(adb(serial, "logcat", "-d", "-s", "Anomalops:I"))
        if match:
            return int(match.group(1))
        time.sleep(POLL_S)
    return None


def inject(serial):
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "start", "-n", ACTIVITY, "--ez", "injectCrash", "true")


def one_round(serial, number, before_sessions):
    before_pid = pid(serial)
    inject(serial)
    elapsed = restart_ms(serial)
    time.sleep(SETTLE_S)
    problems = []
    if elapsed is None:
        problems.append(f"no restart line within {WAIT_S:.0f} s")
    elif elapsed > LIMIT_MS:
        problems.append(f"restart took {elapsed} ms > {LIMIT_MS} ms")
    if not pinned(serial):
        problems.append("task no longer pinned")
    after_pid = pid(serial)
    if not after_pid or after_pid == before_pid:
        problems.append(f"process ID {before_pid!r} -> {after_pid!r}")
    if sessions(serial) != before_sessions:
        problems.append("FR-45 session directories changed")
    status = "ok" if not problems else "FAIL: " + "; ".join(problems)
    print(f"round {number}: {elapsed if elapsed is not None else '-'} ms, {status}")
    return elapsed, problems


def crash_loop(serial):
    """One restart, then a crash right away: no restart, and the system ends the pin."""
    _, problems = one_round(serial, 1, sessions(serial))
    if problems:
        print("crash loop: the first restart already failed")
        return 1
    inject(serial)
    elapsed = restart_ms(serial)
    time.sleep(SETTLE_S)
    problems = []
    if elapsed is not None:
        problems.append(f"restarted in {elapsed} ms")
    if pinned(serial):
        problems.append("task still pinned")
    verdict = "not restarted, pin ended, ok" if not problems else "FAIL: " + "; ".join(problems)
    print(f"crash loop: second crash {verdict}")
    return 0 if not problems else 1


def rounds(serial, count, gap_s):
    before = sessions(serial)
    print(f"{len(before)} session directories before; running {count} rounds, {gap_s:.0f} s apart")
    results = []
    for n in range(count):
        if n:
            time.sleep(gap_s)
        results.append(one_round(serial, n + 1, before))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--gap", type=float, default=GAP_S, help="seconds between rounds")
    parser.add_argument("--crash-loop", action="store_true", help="check that a second crash is not restarted")
    parser.add_argument("--serial")
    args = parser.parse_args()
    if not pinned(args.serial):
        print("the app is not in dive lock: press the dive-lock key and accept the pin dialog first")
        return 1
    if args.crash_loop:
        return crash_loop(args.serial)
    results = rounds(args.serial, args.rounds, args.gap)
    times = sorted(ms for ms, _ in results if ms is not None)
    passed = sum(1 for _, problems in results if not problems)
    if times:
        print(f"restart ms: median {times[len(times) // 2]}, max {times[-1]}")
    print(f"{passed} / {args.rounds} rounds passed")
    return 0 if passed == args.rounds else 1


if __name__ == "__main__":
    sys.exit(main())

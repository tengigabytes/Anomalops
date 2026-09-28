# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the merged AndroidManifest of each :app flavor against NFR-8 / ADR-0010.

Run after a build of both flavors (debug or release), e.g. `./gradlew assembleFossDebug assemblePlayDebug`.
Usage: python scripts/check_flavor_manifests.py [--variant debug|release] [--report]
(default variant: debug, which is what CI builds; exit code 1 on a violation)
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "io.github.tengigabytes.anomalops"

# Permissions each flavor may declare after merging (NFR-8, ADR-0010).
# foss: nothing that reaches the network. play: additionally what Play Billing 9.1.0 merges in
# (measured 2026-09-28, docs/test/m0-play-billing.md); the dependency itself is only added in R3.
BASE = {f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
ALLOWED = {
    "foss": BASE,
    "play": BASE | {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "com.android.vending.BILLING",
    },
}


def merged_manifest(flavor, build_type):
    """The merged manifest of one explicit variant; no guessing between stale debug and release outputs."""
    pattern = os.path.join(ROOT, "app", "build", "intermediates", "merged_manifest",
                           f"{flavor}{build_type.capitalize()}", "**", "AndroidManifest.xml")
    found = sorted(glob.glob(pattern, recursive=True))
    return found[0] if found else None


def summarize(path):
    root = ET.parse(path).getroot()
    perms = {e.get(f"{ANDROID}name") for e in root.iter("uses-permission")}
    components = []
    app = root.find("application")
    for tag in ("activity", "service", "receiver", "provider"):
        for e in app.iter(tag) if app is not None else []:
            components.append(f"{tag}:{e.get(f'{ANDROID}name')}")
    meta = [e.get(f"{ANDROID}name") for e in app.iter("meta-data")] if app is not None else []
    queries = [ET.tostring(q, encoding="unicode").count("<") for q in root.iter("queries")]
    return perms, components, meta, queries


def main():
    report = "--report" in sys.argv
    build_type = sys.argv[sys.argv.index("--variant") + 1] if "--variant" in sys.argv else "debug"
    errors = 0
    for flavor, allowed in ALLOWED.items():
        path = merged_manifest(flavor, build_type)
        if path is None:
            print(f"ERROR {flavor}: merged manifest not found; run the manifest tasks first")
            errors += 1
            continue
        perms, components, meta, queries = summarize(path)
        print(f"== {flavor}{build_type.capitalize()}: {len(perms)} permission(s), {len(components)} component(s)")
        for p in sorted(perms):
            flag = "" if p in allowed else "  <- not allowed"
            print(f"  permission {p}{flag}")
            errors += 0 if (p in allowed or report) else 1
        if report:
            for c in components:
                print(f"  {c}")
            for m in meta:
                print(f"  meta-data {m}")
            print(f"  queries elements: {queries}")
    print(f"{errors} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Enforce the module dependency rules of ADR-0007 by scanning each module's build.gradle.kts.

Usage: python scripts/check_module_deps.py   (exit code 1 on a forbidden or unknown dependency)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT_REF = re.compile(r'project\(\s*"(:[a-z:]+)"\s*\)')

# docs/adr/0007-stack-and-modules.md: which project modules each module may depend on.
ALLOWED = {
    ":app": {":core:camera", ":core:gpu", ":core:imaging", ":core:profile", ":core:store", ":core:telemetry"},
    ":core:camera": {":core:profile"},
    ":core:gpu": {":core:imaging"},  # ADR-0017
    ":core:imaging": set(),  # ADR-0016
    ":core:profile": set(),
    ":core:store": {":core:camera"},
    ":core:telemetry": set(),
    ":tools:probe": {":core:profile"},
}


def build_file(module):
    return os.path.join(ROOT, *module.strip(":").split(":"), "build.gradle.kts")


def included_modules():
    with open(os.path.join(ROOT, "settings.gradle.kts"), encoding="utf-8") as f:
        text = f.read()
    return set(re.findall(r'"(:[a-z:]+)"', text.split("include(", 1)[1])) if "include(" in text else set()


def main():
    errors = []
    modules = included_modules()
    for module in sorted(modules - ALLOWED.keys()):
        errors.append(f"{module}: not listed in ALLOWED; add it to ADR-0007 and this script")
    for module, allowed in ALLOWED.items():
        path = build_file(module)
        if not os.path.exists(path):
            errors.append(f"{module}: missing {os.path.relpath(path, ROOT)}")
            continue
        with open(path, encoding="utf-8") as f:
            deps = set(PROJECT_REF.findall(f.read()))
        for dep in sorted(deps - allowed):
            errors.append(f"{module} -> {dep}: forbidden by ADR-0007")
    for e in errors:
        print(f"ERROR {e}")
    print(f"{len(errors)} error(s) in {len(ALLOWED)} modules")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

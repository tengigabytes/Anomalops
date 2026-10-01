# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""NFR-9: product code carries no model-specific branches; everything per model lives in assets/device-profiles/.

Scans the code (comments stripped) of the product modules outside their test source sets and reports:
  - a device codename as a word: every profile's file name, plus the Pixel codenames in KNOWN_CODENAMES;
  - a Pixel model name such as "Pixel 10" in code or resource values;
  - Build fields that identify a model (MODEL, PRODUCT, ...), and Build.DEVICE used in a comparison.
Build.DEVICE itself is allowed: it is the key that selects the profile. :tools:probe is exempt, because
recording the model is its job.

Usage: python scripts/check_device_neutral.py   (exit code 1 on a finding)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE_DIR = os.path.join(ROOT, "assets", "device-profiles")
PRODUCT_MODULES = ["app", "core/camera", "core/gpu", "core/profile", "core/store", "core/telemetry"]
TEST_SOURCE_SETS = {"test", "androidTest", "testFixtures"}
SUFFIXES = (".kt", ".kts", ".xml", ".java")

# Pixel 6 to Pixel 10 codenames, from memory and not checked against a device list (NFR-9 covers 6 Pro to 11 Pro).
# Profiles add their own file names, so a model's codename is checked as soon as its profile exists.
KNOWN_CODENAMES = {
    "oriole", "raven", "bluejay", "panther", "cheetah", "lynx", "tangorpro", "felix",
    "shiba", "husky", "akita", "tokay", "caiman", "komodo", "comet", "tegu",
    "frankel", "blazer", "mustang", "rango",
}
MODEL_NAME = re.compile(r"\bPixel\s*\d+")
MODEL_FIELDS = re.compile(r"\bBuild\.(MODEL|PRODUCT|HARDWARE|BOARD|BRAND|MANUFACTURER|FINGERPRINT|ID)\b")
DEVICE_COMPARE = re.compile(
    r"Build\.DEVICE\s*(==|!=|\.equals|\.startsWith|\.contains|in\b)|(==|!=)\s*Build\.DEVICE|when\s*\(\s*Build\.DEVICE"
)


def strip_kotlin_comments(text):
    """Blank out // and /* */ comments, keeping strings (so "https://" survives) and line numbers."""
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end])
            i = end
        elif text[i] in "\"'":
            quote, j = text[i], i + 1
            while j < n and text[j] != quote and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("\n" * text.count("\n", i, end))
            i = end
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def strip_xml_comments(text):
    return re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def product_files():
    for module in PRODUCT_MODULES:
        base = os.path.join(ROOT, *module.split("/"))
        build = os.path.join(base, "build.gradle.kts")
        if os.path.exists(build):
            yield build
        src = os.path.join(base, "src")
        if not os.path.isdir(src):
            continue
        for source_set in sorted(os.listdir(src)):
            if source_set in TEST_SOURCE_SETS:
                continue
            for dirpath, _, names in os.walk(os.path.join(src, source_set)):
                for name in sorted(names):
                    if name.endswith(SUFFIXES):
                        yield os.path.join(dirpath, name)


def codenames():
    names = set(KNOWN_CODENAMES)
    if os.path.isdir(PROFILE_DIR):
        names |= {f[:-5] for f in os.listdir(PROFILE_DIR) if f.endswith(".json")}
    return re.compile(r"\b(" + "|".join(sorted(map(re.escape, names))) + r")\b")


def findings(path, codename):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    code = strip_xml_comments(text) if path.endswith(".xml") else strip_kotlin_comments(text)
    for number, line in enumerate(code.splitlines(), start=1):
        for pattern, what in (
            (codename, "device codename"),
            (MODEL_NAME, "model name"),
            (MODEL_FIELDS, "model-identifying Build field"),
            (DEVICE_COMPARE, "branch on Build.DEVICE"),
        ):
            match = pattern.search(line)
            if match:
                yield number, f"{what} '{match.group(0)}'"


def main():
    codename = codenames()
    errors, count = [], 0
    for path in product_files():
        count += 1
        for number, message in findings(path, codename):
            errors.append(f"{os.path.relpath(path, ROOT)}:{number}: {message}; move it to assets/device-profiles/")
    for e in errors:
        print(f"ERROR {e}")
    print(f"{len(errors)} error(s) in {count} files")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

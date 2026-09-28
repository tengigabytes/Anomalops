# SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Enforce file-size limits, docs indexes and relative links (docs/dev/code-structure.md).

Usage: python scripts/check_limits.py   (exit code 1 when any hard limit is violated)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", ".gradle", ".idea", "build", "node_modules", ".claude"}
LINK_RE = re.compile(r"\]\(([^)\s#]+)(?:#[^)]*)?\)")

# (soft_lines, hard_lines, hard_bytes or None)
CODE = (200, 300, None)
TEST = (300, 400, None)
GRADLE = (100, 150, None)
SCRIPT = (150, 200, None)
DOC = (250, 300, 20 * 1024)
DOC_SOFT_BYTES = 16 * 1024


def rule_for(rel):
    """Return the limit tuple for a repo-relative path, or None when the file is exempt."""
    name = os.path.basename(rel)
    parts = rel.split("/")
    if name == "CLAUDE.md":
        hard = 60 if len(parts) == 1 else 30
        return (hard, hard, None)
    if name.endswith((".gradle.kts", ".gradle")):
        return GRADLE
    if name.endswith((".kt", ".java")):
        return TEST if ("test" in parts or "androidTest" in parts) else CODE
    if name.endswith((".py", ".sh")):
        return SCRIPT
    if name.endswith(".md"):
        return DOC
    return None


def iter_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            yield path, os.path.relpath(path, ROOT).replace(os.sep, "/")


def check_sizes(errors, warnings):
    for path, rel in iter_files():
        rule = rule_for(rel)
        if rule is None:
            continue
        soft, hard, hard_bytes = rule
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = sum(1 for _ in f)
        size = os.path.getsize(path)
        if lines > hard or (hard_bytes and size > hard_bytes):
            errors.append(f"{rel}: {lines} lines / {size} bytes exceeds hard limit {hard} lines"
                          + (f" / {hard_bytes} bytes" if hard_bytes else ""))
        elif lines > soft or (hard_bytes and size > DOC_SOFT_BYTES):
            warnings.append(f"{rel}: {lines} lines / {size} bytes exceeds soft limit {soft} lines")


def check_links(errors):
    for path, rel in iter_files():
        if not rel.endswith(".md"):
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for target in LINK_RE.findall(text):
            if re.match(r"^[a-z]+:", target):
                continue  # external URL or mailto
            if not os.path.exists(os.path.join(os.path.dirname(path), target)):
                errors.append(f"{rel}: broken link -> {target}")


def check_doc_indexes(errors):
    """Every directory under docs/ needs README.md that links each sibling doc and subdirectory."""
    docs = os.path.join(ROOT, "docs")
    for dirpath, dirnames, filenames in os.walk(docs):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        rel_dir = os.path.relpath(dirpath, ROOT).replace(os.sep, "/")
        index = os.path.join(dirpath, "README.md")
        if not os.path.exists(index):
            errors.append(f"{rel_dir}/: missing README.md index")
            continue
        with open(index, encoding="utf-8") as f:
            targets = {os.path.normpath(t) for t in LINK_RE.findall(f.read())}
        expected = [n for n in filenames
                    if n.endswith(".md") and n not in ("README.md", "CLAUDE.md") and not n.endswith(".en.md")]
        expected += [os.path.join(d, "README.md") for d in dirnames]
        for item in expected:
            if os.path.normpath(item) not in targets:
                errors.append(f"{rel_dir}/README.md: index does not link {item}")


def main():
    errors, warnings = [], []
    check_sizes(errors, warnings)
    check_links(errors)
    check_doc_indexes(errors)
    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print(f"{len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

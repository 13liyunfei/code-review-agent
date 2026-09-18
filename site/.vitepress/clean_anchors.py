#!/usr/bin/env python3
"""Clean code-anchor callouts from the book (source + site copy).

Removes top-of-chapter blockquote callouts:
  - '> 📌 本讲代码锚点：...'
  - '> 🔁 对照锚点：...'   (same class of code anchor)
Keeps '> 🎯 导读问题' callouts.

Also fixes the opening chapter (01-开篇) where it says "42 讲分别在解决什么"
(the whole book is now 65 chapters: 上篇 42 + 下篇 23) -> "65 讲分别在解决什么".

Runs on both the local source book and the VitePress site copy so they stay in sync.
"""
import glob
import os
import sys

TARGETS = [
    "/Users/yunfei/WorkBuddy/2026-09-11-23-45-agent-column/book",
    "/Users/yunfei/IdeaProjects/code-review-agent/site/zh/book",
]

ANCHOR_PREFIXES = ("本讲代码锚点", "对照锚点")
OPENING_FIX = ("42 讲分别在解决什么", "65 讲分别在解决什么")


def process_file(path: str) -> bool:
    with open(path, encoding="utf-8") as fh:
        lines = fh.readlines()
    out = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        # Opening-chapter count fix (whole-book number)
        if OPENING_FIX[0] in line:
            line = line.replace(OPENING_FIX[0], OPENING_FIX[1])
            changed = True
        # Remove anchor callout blockquote lines (and same-anchor continuation)
        stripped = line.lstrip()
        if stripped.startswith(">") and any(p in line for p in ANCHOR_PREFIXES):
            changed = True
            i += 1
            while (i < len(lines) and lines[i].lstrip().startswith(">")
                   and any(p in lines[i] for p in ANCHOR_PREFIXES)):
                changed = True
                i += 1
            continue
        out.append(line)
        i += 1
    if changed:
        with open(path, "w", encoding="utf-8") as fh:
            fh.writelines(out)
        return True
    return False


def main():
    for base in TARGETS:
        if not os.path.isdir(base):
            print(f"SKIP (missing): {base}")
            continue
        n = 0
        for f in glob.glob(os.path.join(base, "**", "*.md"), recursive=True):
            if process_file(f):
                n += 1
        print(f"{base}: {n} files changed")


if __name__ == "__main__":
    sys.exit(main())

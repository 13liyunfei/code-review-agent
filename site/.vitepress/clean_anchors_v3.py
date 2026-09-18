#!/usr/bin/env python3
# SAFE anchor removal: delete ONLY the single line `> 📌 代码锚点：...`.
# Do NOT touch any following `>` callouts (导读问题 / 相关面试考点 / etc.).
import os, re, sys

DIRS = sys.argv[1:] or [
    "/Users/yunfei/WorkBuddy/2026-09-11-23-45-agent-column/book",
    "/Users/yunfei/IdeaProjects/code-review-agent/site/zh/book",
]

anchor_re = re.compile(r'^>\s*📌\s*代码锚点：')

total = 0
files = 0
for base in DIRS:
    for root, _, fs in os.walk(base):
        for fn in fs:
            if not fn.endswith(".md"):
                continue
            p = os.path.join(root, fn)
            with open(p, encoding="utf-8") as fh:
                lines = fh.readlines()
            out = [l for l in lines if not anchor_re.match(l)]
            removed = len(lines) - len(out)
            if removed:
                with open(p, "w", encoding="utf-8") as fh:
                    fh.writelines(out)
                files += 1
                total += removed
print(f"files touched: {files}, anchor lines removed: {total}")

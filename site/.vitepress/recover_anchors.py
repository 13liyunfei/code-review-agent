#!/usr/bin/env python3
# RECOVERY: the v2 clean script over-deleted (it removed `> 🎯 相关面试考点` etc. as
# "continuation" lines). This restores the pre-v2 top callout block of the 22 source
# files by copying the (clean, git-restored) callout block from the site copy, which is
# line-identical to the source's original top callout. Then we re-apply a SAFE removal
# that deletes ONLY the single `> 📌 代码锚点：` line.
import os, re

SRC = "/Users/yunfei/WorkBuddy/2026-09-11-23-45-agent-column/book"
SITE = "/Users/yunfei/IdeaProjects/code-review-agent/site/zh/book"

anchor_re = re.compile(r'^>\s*📌\s*代码锚点：')

def extract_callout(lines):
    # find title line (# ...), then collect consecutive '>' lines after the first blank
    out = []
    title_idx = None
    for i, l in enumerate(lines):
        if l.startswith("# ") and title_idx is None:
            title_idx = i
            break
    if title_idx is None:
        return None, None
    j = title_idx + 1
    # skip blank(s) after title
    while j < len(lines) and lines[j].strip() == "":
        j += 1
    start = j
    while j < len(lines) and lines[j].lstrip().startswith(">"):
        out.append(lines[j])
        j += 1
    return start, out

def process(src_path):
    rel = os.path.relpath(src_path, SRC)
    site_path = os.path.join(SITE, rel)
    with open(src_path, encoding="utf-8") as fh:
        src_lines = fh.readlines()
    with open(site_path, encoding="utf-8") as fh:
        site_lines = fh.readlines()
    site_start, site_callout = extract_callout(site_lines)
    if site_callout is None:
        print(f"  WARN no callout in site for {rel}")
        return False
    # in source, find title then the (damaged) callout region
    title_idx = None
    for i, l in enumerate(src_lines):
        if l.startswith("# ") and title_idx is None:
            title_idx = i
            break
    if title_idx is None:
        return False
    j = title_idx + 1
    while j < len(src_lines) and src_lines[j].strip() == "":
        j += 1
    # region [j, k) are current '>' lines (some may be missing); k = first non-'>' line
    k = j
    while k < len(src_lines) and src_lines[k].lstrip().startswith(">"):
        k += 1
    # Replace src_lines[j:k] with the full site callout (restores anchor + all '>' lines)
    new_lines = src_lines[:j] + [l if l.endswith("\n") else l + "\n" for l in site_callout] + src_lines[k:]
    with open(src_path, "w", encoding="utf-8") as fh:
        fh.writelines(new_lines)
    return True

count = 0
for root, _, files in os.walk(SRC):
    for fn in files:
        if not fn.endswith(".md"):
            continue
        rel = os.path.relpath(os.path.join(root, fn), SRC)
        site_path = os.path.join(SITE, rel)
        if not os.path.exists(site_path):
            continue
        with open(site_path, encoding="utf-8") as fh:
            if not any(anchor_re.match(l) for l in fh.readlines()):
                continue  # only the 22 whose site counterpart has an anchor
        if process(os.path.join(root, fn)):
            count += 1
print(f"recovered {count} source files")

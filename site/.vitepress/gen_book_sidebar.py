#!/usr/bin/env python3
"""Regenerate book-sidebar.mjs from site/zh/book with numerically-sorted modules.

The previous version sorted module directories as strings, which put M10
between M1 and M2. This script sorts by the integer inside 'M<n>' so the
sidebar reads M1, M2, ... M10. Chapter items are sorted by their leading
number as well.
"""
import os
import re
import sys

BOOK_DIR = os.path.join(os.path.dirname(__file__), "..", "zh", "book")
OUT = os.path.join(os.path.dirname(__file__), "book-sidebar.mjs")


def mod_key(name: str) -> int:
    m = re.search(r"M(\d+)", name)
    return int(m.group(1)) if m else 0


def chap_key(name: str) -> int:
    m = re.search(r"^(\d+)-", name)
    return int(m.group(1)) if m else 0


def main():
    modules = []
    for entry in os.listdir(BOOK_DIR):
        full = os.path.join(BOOK_DIR, entry)
        if os.path.isdir(full) and entry.startswith("M"):
            modules.append(entry)
    modules.sort(key=mod_key)

    blocks = []
    for mod in modules:
        mod_dir = os.path.join(BOOK_DIR, mod)
        chapters = [
            f for f in os.listdir(mod_dir)
            if f.endswith(".md") and not f.startswith("index")
        ]
        chapters.sort(key=chap_key)
        items = []
        for ch in chapters:
            base = ch[:-3]  # strip .md
            items.append(
                '      {\n'
                f'        text: "{base}",\n'
                f'        link: "/zh/book/{mod}/{base}"\n'
                '      },'
            )
        block = (
            '  {\n'
            f'    text: "{mod}",\n'
            '    items: [\n'
            + "\n".join(items)
            + "\n    ]\n  },"
        )
        blocks.append(block)

    content = "export default\n[\n" + "\n".join(blocks) + "\n]\n"
    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(content)
    print(f"wrote {OUT}: {len(modules)} modules, "
          f"{sum(len(os.listdir(os.path.join(BOOK_DIR, m))) for m in modules)} chapter files")


if __name__ == "__main__":
    sys.exit(main())

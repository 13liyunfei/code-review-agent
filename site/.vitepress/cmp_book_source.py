#!/usr/bin/env python3
"""比对 book/ 源稿与 site/zh/book 站点源文件的正文一致性。

图在两边的形态不同（book 侧是 ```mermaid 源代码块；site 侧是编译好的
<img class="mermaid-svg" ... /> 标签），因此先把图从两边一起去掉再比正文。
"""
import os, re, sys, difflib, glob, html

BOOK = os.path.expanduser(os.environ.get(
    "BOOK_DIR", "~/WorkBuddy/2026-09-11-23-45-agent-column/book"))
SITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "zh", "book")

SKIP_DIRS = {"M1-LLM接入与协议的替身", "__pycache__"}

MERMAID_BLOCK = re.compile(r"^[ \t]*```mermaid\b.*?^[ \t]*```[ \t]*$", re.S | re.M)
IMG_TAG_LINE = re.compile(r"^[ \t]*<img class=\"(?:mermaid|arch)-svg\".*?/?>[ \t]*$", re.M)


CODE_HANDLEBAR = re.compile(r"<code>(.*?)</code>", re.S)
TICK = re.compile(r"`([^`\n]*)`")


def unwrap_code(text):
    """把 <code>x</code> 与 `x` 统一成同一形态（去包裹 + 反转义）。"""
    def h(m):
        return "\x00" + html.unescape(m.group(1)) + "\x00"
    return TICK.sub(h, CODE_HANDLEBAR.sub(h, text))


def norm(text, side):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if side == "book":
        text = MERMAID_BLOCK.sub("", text)
    # 两侧都要去掉「用 <img> 引外部 svg 文件」的图行（book 与 site 都这么引 arch 图）
    text = IMG_TAG_LINE.sub("", text)
    text = text.replace(" v-pre", "")
    # 行内代码：book 侧写 `x`，site 侧被静态化成 <code>x</code>，且实体被转义 → 归一
    text = unwrap_code(text)
    lines = [ln.rstrip() for ln in text.split("\n")]
    # 折叠 3 个以上连续空行为 1 个
    out, blank = [], 0
    for ln in lines:
        if ln == "":
            blank += 1
            if blank > 1:
                continue
        else:
            blank = 0
        out.append(ln)
    return "\n".join(out).strip()


def rels(root):
    got = {}
    for p in glob.glob(os.path.join(root, "**", "*.md"), recursive=True):
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        if rel.split("/")[0] in SKIP_DIRS:
            continue
        if os.path.basename(rel).startswith("_") or os.path.basename(rel) == "README.md":
            continue
        got[rel] = p
    return got


def main():
    b, s = rels(BOOK), rels(SITE)
    only_book = sorted(set(b) - set(s))
    only_site = sorted(set(s) - set(b))
    both = sorted(set(b) & set(s))
    print(f"book {len(b)} 个 md，site {len(s)} 个 md，共有 {len(both)}")
    if only_book:
        print(f"仅 book 有 {len(only_book)}：{only_book}")
    if only_site:
        print(f"仅 site 有 {len(only_site)}：{only_site}")

    diff, same = [], 0
    for rel in both:
        nb, ns = norm(open(b[rel], encoding="utf-8").read(), "book"), norm(open(s[rel], encoding="utf-8").read(), "site")
        if nb == ns:
            same += 1
            continue
        d = list(difflib.unified_diff(nb.split("\n"), ns.split("\n"), "book/" + rel, "site/" + rel, n=0, lineterm=""))
        diff.append((rel, d))

    print(f"\n正文逐字一致 {same}/{len(both)}；有差异 {len(diff)}")
    for rel, d in diff:
        print(f"\n=== {rel}  差异行 {len([x for x in d if x[:1] in '+-' and x[:3] not in ('+++', '---')])}")
        for ln in d[2:14]:
            print("   " + ln[:160])
    return 0 if not diff and not only_book and not only_site else 1


if __name__ == "__main__":
    sys.exit(main())

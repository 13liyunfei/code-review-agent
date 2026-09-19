#!/usr/bin/env python3
"""Normalize inline figure tags in site/zh/book/**/*.md so markdown-it keeps them as raw HTML.

背景（2026-09-20 排查）：

专栏各讲的 mermaid 图在接入 VitePress 时被一次性转换成了内联标签：

    <img class="mermaid-svg" src="/zh/book-assets/diag-XXXX.svg" alt="…" />

其中 alt 取的是图前面的那段引导语（形如 `> 本讲是全书**唯一**一讲"以跑通为目标"的课。…`）。
引导语里含中文引号对 `"…"`（U+201C/U+201D）时本没问题，但其中一部分用的是**ASCII 双引号**，
于是标签变成 alt="…"以跑通为目标"的课。…" —— 属性被提前截断。

markdown-it 的 HTML 块识别要过 `HTML_OPEN_CLOSE_TAG_RE`，属性值必须是
`"…"` / `'…'` / 无引号裸值三者之一；上面这种串不匹配任何一支，
于是**整个标签被当成普通文本转义**，读者在页面上看到的是
`<img class="mermaid-svg" src="…" />` 这行源码，而不是图。

本脚本把 alt 值按 HTML 属性规则重新序列化（`&`→`&amp;`、`"`→`&quot;`、
`<`/`>`→`&lt;`/`&gt;`），其余部分逐字节不动。

- 幂等：先 unescape 再 escape，重复执行结果一致。
- 默认只报不改；`--check` 只看，`--write` 才落盘（或直接给 `--check` 与 `--write` 二选一）。

用法：
    python3 site/.vitepress/fix_book_figures.py            # 报告（不改文件）
    python3 site/.vitepress/fix_book_figures.py --write    # 落盘
    python3 site/.vitepress/fix_book_figures.py --selftest # 自测判据
"""
import argparse
import html
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
BOOK_DIR = os.path.normpath(os.path.join(HERE, "..", "zh", "book"))

# 允许 alt 里有换行外的任意字符；用非贪婪 + 锚定终止串，这样「alt 里混了裸引号」也能整段吃进来。
TAG_RE = re.compile(
    r'<img class="(?P<cls>mermaid|arch)-svg"'
    r' src="(?P<src>[^"]*)"'
    r' alt="(?P<alt>.*?)"'
    r'\s*/?>',
    re.DOTALL,
)


# 完整实体（&amp; / &#34; / &#x22; 这类）；用于区分「裸 &」与「已是实体」
ENTITY = re.compile(r"&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#\d{1,7}|#[xX][0-9a-fA-F]{1,6});")


def has_bare_amp(alt: str) -> bool:
    return "&" in ENTITY.sub("", alt)


def needs_fix(alt: str) -> bool:
    """alt 是否不在「属性安全」的规范形里。

    口径必须只看「会不会打断属性」——`&quot;` 里也有 `&`，但它已经是实体、
    是规范形的一部分，不能算待修（否则每次跑都报一遍，判据就失去意义）。
    """
    return bool(re.search(r'["<>]', alt)) or has_bare_amp(alt)


def escape_attr(alt_raw: str) -> str:
    """把 alt 原值转成属性安全形。

    - 已是规范形 → 逐字节不动（保证幂等、diff 最小）
    - 否则先 unescape 再 escape（这样「半转义」的输入也能得到正确结果）
    - 只补 `"` 为 `&quot;`，不额外把 `'` 转掉（值本身用双引号包裹，无需转）
    """
    if not needs_fix(alt_raw):
        return alt_raw
    return html.escape(html.unescape(alt_raw), quote=False).replace('"', "&quot;")


def rewrite(text: str):
    """返回 (新文本, 改动列表)。改动列表 = [(cls, src, 旧 alt, 新 alt), ...]"""
    changes = []

    def sub(m):
        alt_raw = m.group("alt")
        new_alt = escape_attr(alt_raw)
        if new_alt == alt_raw:
            return m.group(0)
        changes.append((m.group("cls"), m.group("src"), alt_raw, new_alt))
        # 只替换 alt 的值，标签其余字节保持原样（让 diff 最小、可逐字复核）
        # 注意：m.start/end 是**全串绝对偏移**，而 tag 是子串，必须减去 m.start(0)，
        # 否则切片越界被静默钳制 → 返回「原标签 + 转义后的 alt」这种坏结果。
        base = m.start(0)
        tag = m.group(0)
        prefix = tag[: m.start("alt") - base]
        suffix = tag[m.end("alt") - base:]
        return prefix + new_alt + suffix

    return TAG_RE.sub(sub, text), changes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="落盘（默认只报告）")
    ap.add_argument("--selftest", action="store_true", help="跑判据自测")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not os.path.isdir(BOOK_DIR):
        print("目录不存在: %s" % BOOK_DIR, file=sys.stderr)
        return 2

    total_tags = total_bad = total_fixed = 0
    touched_files = []
    for root, dirs, files in os.walk(BOOK_DIR):
        dirs.sort()
        for fn in sorted(files):
            if not fn.endswith(".md"):
                continue
            path = os.path.join(root, fn)
            src = open(path, encoding="utf-8").read()
            tags = TAG_RE.findall(src)
            total_tags += len(tags)
            bad = [m for m in TAG_RE.finditer(src) if needs_fix(m.group("alt"))]
            if not bad:
                continue
            total_bad += len(bad)
            new, changes = rewrite(src)
            if "".join(m.group("alt") for m in TAG_RE.finditer(new)) != \
               "".join(escape_attr(m.group("alt")) for m in TAG_RE.finditer(src)):
                print("！自检失败（改写后 alt 集合不一致）: %s" % path, file=sys.stderr)
                return 3
            total_fixed += len(changes)
            rel = os.path.relpath(path, REPO_ROOT)
            touched_files.append(rel)
            print("%-3d 处  %s" % (len(changes), rel))
            if args.write:
                open(path, "w", encoding="utf-8").write(new)

    print()
    print("扫描 %s" % BOOK_DIR)
    print("内联图标签 %d 个；alt 属性需转义的 %d 个，分布在 %d 个文件"
          % (total_tags, total_bad, len(touched_files)))
    if args.write:
        print("已写入 %d 处" % total_fixed)
        # 落盘后立刻复检：再跑一遍应当 0 处待修（幂等 + 真修好）
        remain = 0
        for root, dirs, files in os.walk(BOOK_DIR):
            for fn in files:
                if fn.endswith(".md"):
                    s = open(os.path.join(root, fn), encoding="utf-8").read()
                    remain += sum(1 for m in TAG_RE.finditer(s) if needs_fix(m.group("alt")))
        print("复检：仍待修 %d 处" % remain)
        return 0 if remain == 0 else 4
    print("（未落盘；加 --write 生效）")
    return 0


def selftest() -> int:
    """判据自测：一个必报样本 + 一个必放过样本，另加幂等检查。"""
    broken = '<img class="mermaid-svg" src="/zh/book-assets/diag-0001.svg" alt="他说"你好"就走。" />'
    clean = '<img class="mermaid-svg" src="/zh/book-assets/diag-0001.svg" alt="他说你好就走。" />'
    already = '<img class="mermaid-svg" src="/zh/book-assets/diag-0001.svg" alt="他说&quot;你好&quot;就走。" />'
    ok = True

    n_bad = sum(1 for m in TAG_RE.finditer(broken) if needs_fix(m.group("alt")))
    print("必报样本（裸引号）待修数 = %d，期望 1 → %s" % (n_bad, "通过" if n_bad == 1 else "失败"))
    ok &= n_bad == 1

    n_clean = sum(1 for m in TAG_RE.finditer(clean) if needs_fix(m.group("alt")))
    print("必放过样本（无引号）待修数 = %d，期望 0 → %s" % (n_clean, "通过" if n_clean == 0 else "失败"))
    ok &= n_clean == 0

    out, ch = rewrite(broken)
    print("改写后 = %s" % out)
    ok &= '&quot;' in out and '"你好"' not in out
    # 前缀补一段文本，让「绝对偏移 ≠ 子串内偏移」——这正是首版踩到的切片 bug 的触发条件
    prefixed = "一些前言。\n\n" + broken + "\n\n后记。"
    out_p, ch_p = rewrite(prefixed)
    ok &= out_p == "一些前言。\n\n" + out + "\n\n后记。"
    print("带前缀改写一致 → %s" % ("通过" if out_p == "一些前言。\n\n" + out + "\n\n后记。" else "失败"))

    out2, ch2 = rewrite(out)
    print("幂等复跑改动数 = %d，期望 0 → %s" % (len(ch2), "通过" if len(ch2) == 0 else "失败"))
    ok &= len(ch2) == 0

    n_already = sum(1 for m in TAG_RE.finditer(already) if needs_fix(m.group("alt")))
    print("已转义样本（&quot;）待修数 = %d，期望 0 → %s" % (n_already, "通过" if n_already == 0 else "失败"))
    ok &= n_already == 0

    print("\n自测%s" % ("全部通过" if ok else "未通过"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

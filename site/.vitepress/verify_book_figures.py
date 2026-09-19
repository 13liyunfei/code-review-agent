#!/usr/bin/env python3
"""验收 site/zh/book 里的内联插图：源文件侧 + 构建产物侧，各判一次。

为什么要判「构建产物」而不是只看源文件：
专栏各讲的 `<img class="mermaid-svg" …>` 是内联 HTML。只要 alt 里混进一个裸双引号，
属性就被提前截断，markdown-it 认不出这是 HTML 块 → **把整行转义成文本**。
结果是构建照常成功、退出码 0，读者却在页面上看到一行 `<img class="…" />` 源码。
这类「静默降级」只有扫构建产物才拦得住。

判据（每条都对应一个真实失效面）：
  ① 源文件：每个图标签的 alt 必须是「属性安全形」，且标签数与 book-assets 里的图数一致
  ② 产物：不允许出现被转义的图标签（读者看到源码）
  ③ 产物：真正渲染出的 `<img class="mermaid-svg">` 数必须与源文件侧标签数相等（防「少画了几张」）
  ④ 产物：每个 src 指向的资源必须真实存在（防 404 白框）

用法：
    python3 verify_book_figures.py --md                  # 判源文件（快，构建前）
    python3 verify_book_figures.py --dist .vitepress/dist # 判产物（真，构建后）
    python3 verify_book_figures.py --selftest            # 判据自测
退出码非 0 即失败。
"""
import argparse
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SITE_DIR = os.path.normpath(os.path.join(HERE, ".."))
BOOK_DIR = os.path.normpath(os.path.join(HERE, "..", "zh", "book"))
ASSET_DIR = os.path.normpath(os.path.join(HERE, "..", "public", "zh", "book-assets"))

sys.path.insert(0, HERE)
from fix_book_figures import TAG_RE, needs_fix  # noqa: E402

# markdown-it 转义后，读者看到的是这个（把它当「坏」的指纹）
ESCAPED = '&lt;img class=&quot;mermaid-svg&quot;'
RENDERED = '<img class="mermaid-svg"'
SRC_RE = re.compile(r'src="(/zh/book-assets/[^"]+)"')


def scan_md():
    """→ (mermaid 标签数, arch 标签数, 待修列表, src 集合, 每文件 mermaid 标签数)"""
    n_mermaid = n_arch = 0
    bad, srcs, per_file = [], set(), {}
    for root, dirs, files in os.walk(BOOK_DIR):
        dirs.sort()
        for fn in sorted(files):
            if not fn.endswith(".md"):
                continue
            path = os.path.join(root, fn)
            rel = os.path.relpath(path, SITE_DIR)
            text = open(path, encoding="utf-8").read()
            ms = list(TAG_RE.finditer(text))
            mermaid = [m for m in ms if m.group("cls") == "mermaid"]
            if mermaid:
                per_file[rel] = len(mermaid)
            n_mermaid += len(mermaid)
            n_arch += len(ms) - len(mermaid)
            for m in ms:
                srcs.add(m.group("src"))
                if needs_fix(m.group("alt")):
                    bad.append((rel, m.group("alt")[:60]))
    return n_mermaid, n_arch, bad, srcs, per_file


def check_md(verbose=True):
    n_mermaid, n_arch, bad, srcs, per_file = scan_md()
    problems = []

    if bad:
        problems.append("源文件里有 %d 个图标签的 alt 未转义（会被 markdown-it 整行转义成文本）" % len(bad))
        for rel, frag in bad[:8]:
            problems.append("    %s  alt=%s…" % (rel, frag))

    # 资源校验：book-assets 下的图必须逐个存在；其余（如 /architecture-layered.svg）
    # 落在 public 根，按 public 目录校验 —— 两种来源的落点不同，不能混用一把尺子量。
    public_dir = os.path.join(SITE_DIR, "public")
    try:
        assets_on_disk = {"/zh/book-assets/" + f for f in os.listdir(ASSET_DIR) if f.endswith(".svg")}
    except OSError as e:
        problems.append("读不到资源目录 %s: %s" % (ASSET_DIR, e))
        assets_on_disk = set()
    book_srcs = {s for s in srcs if s.startswith("/zh/book-assets/")}
    other_srcs = sorted(srcs - book_srcs)

    missing = sorted(book_srcs - assets_on_disk)
    if missing:
        problems.append("源文件引用了不存在的 diagram 资源 %d 个：%s" % (len(missing), missing[:5]))
    unused = sorted(assets_on_disk - book_srcs)
    if unused:
        problems.append("book-assets 里有未被任何讲引用的图 %d 个：%s" % (len(unused), unused[:5]))
    for s in other_srcs:
        if not os.path.exists(os.path.join(public_dir, s.lstrip("/"))):
            problems.append("源文件引用的非 diagram 资源不存在：%s（应在 site/public/ 下）" % s)

    if verbose:
        print("[MD] mermaid 图标签 %d 个（%d 个文件）+ arch 图 %d 个；"
              "diagram 资源引用 %d 个 / 磁盘 %d 个"
              % (n_mermaid, len(per_file), n_arch, len(book_srcs), len(assets_on_disk)))
    return n_mermaid, n_arch, problems


def check_dist(dist_dir):
    book_out = os.path.join(dist_dir, "zh", "book")
    if not os.path.isdir(book_out):
        return ["产物目录不存在: %s" % book_out]

    md_tags, md_arch, md_bad, md_srcs, _ = scan_md()
    problems = []
    escaped_hits, rendered = [], 0
    pages = 0
    missing_assets = set()

    for root, dirs, files in os.walk(book_out):
        for fn in sorted(files):
            if not fn.endswith(".html"):
                continue
            pages += 1
            path = os.path.join(root, fn)
            html_text = open(path, encoding="utf-8").read()
            n_esc = html_text.count(ESCAPED)
            n_ren = html_text.count(RENDERED)
            rendered += n_ren
            if n_esc:
                escaped_hits.append((os.path.relpath(path, book_out), n_esc))
            for m in re.finditer(r'<img class="mermaid-svg" src="(/zh/book-assets/[^"]+)"', html_text):
                rel_asset = m.group(1).lstrip("/")
                if not os.path.exists(os.path.join(dist_dir, rel_asset)):
                    missing_assets.add(m.group(1))

    if escaped_hits:
        problems.append("产物里有 %d 处图标签被转义成文本（读者会看到源码而不是图）" % sum(h[1] for h in escaped_hits))
        for rel, n in escaped_hits[:8]:
            problems.append("    %d 处  %s" % (n, rel))
    if rendered != md_tags:
        problems.append("产出的图 %d 张，源文件侧 %d 张 —— 不一致（有图没画出来）" % (rendered, md_tags))
    if missing_assets:
        problems.append("产物里引用了不存在的资源 %d 个：%s" % (len(missing_assets), sorted(missing_assets)[:5]))
    if md_bad:
        problems.append("源文件侧仍有 %d 个 alt 未转义（先跑 fix_book_figures.py --write）" % len(md_bad))

    print("[DIST] 扫 %d 个页面：渲染出 %d 张 mermaid 图（源 %d 张，另有 arch 图 %d 张）；被转义 %d 处"
          % (pages, rendered, md_tags, md_arch, sum(h[1] for h in escaped_hits)))
    return problems


def selftest():
    """判据自测：一个必报样本 + 一个必放过样本。"""
    ok = True
    d = tempfile.mkdtemp(prefix="verifyfig-")
    out = os.path.join(d, "zh", "book")
    os.makedirs(out)

    # ① 必报：产物里有被转义的图标签
    bad_html = ('<html><body><p>%s src=&quot;/zh/book-assets/diag-0001.svg&quot; alt=&quot;x&quot; /&gt;</p></body></html>'
                % ESCAPED)
    open(os.path.join(out, "a.html"), "w", encoding="utf-8").write(bad_html)
    probs_bad = [p for p in check_dist(d) if "被转义成文本" in p]
    print("必报样本（含转义图标签）→ 报出 %d 条转义告警，期望 ≥1 → %s"
          % (len(probs_bad), "通过" if probs_bad else "失败"))
    ok &= bool(probs_bad)

    # ② 必放过：只有正常 <img>，且数量与源文件一致
    md_tags, _, _, _, _ = scan_md()
    clean_html = ('<html><body>' + RENDERED + ' src="/zh/book-assets/diag-0001.svg" alt="x" /></body></html>')
    open(os.path.join(out, "a.html"), "w", encoding="utf-8").write(clean_html)
    probs_clean = [p for p in check_dist(d) if "被转义成文本" in p]
    print("必放过样本（只有正常 <img>）→ 报出 %d 条转义告警，期望 0 → %s"
          % (len(probs_clean), "通过" if not probs_clean else "失败"))
    ok &= not probs_clean
    # 该样本张数（1）≠ 源文件张数（%d），应当被「张数不一致」这一条抓到 —— 证明该判据也活着
    probs_count = [p for p in check_dist(d) if "不一致" in p]
    print("张数判据自测：期望报出「不一致」（样本 1 张 vs 源 %d 张）→ %s"
          % (md_tags, "通过" if probs_count else "失败"))
    ok &= bool(probs_count)

    import shutil
    shutil.rmtree(d, ignore_errors=True)
    print("\n自测%s" % ("全部通过" if ok else "未通过"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--md", action="store_true", help="判源文件")
    ap.add_argument("--dist", metavar="DIR", help="判构建产物目录")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    problems = []
    if args.md:
        problems += check_md()[2]
    if args.dist:
        problems += check_dist(args.dist)
    if not args.md and not args.dist:
        problems += check_md()[2]

    if problems:
        print("\n失败 %d 项：" % len(problems))
        for p in problems:
            print("  ✗ %s" % p)
        return 1
    print("\n通过：源文件与产物两侧的插图判据全绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())

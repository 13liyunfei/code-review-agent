#!/usr/bin/env python3
"""从专栏源稿 book/ 生成 VitePress 站点源 site/zh/book/** 与图资产 site/public/zh/book-assets/**。

为什么要这个脚本：
    此前 `site/zh/book/**` 是一次性脚本的产物，脚本本身没进仓库，于是**真源不唯一**——
    站点侧与源稿侧的图早已不同源（实测 130 张图里只有 22 张与源稿哈希一致）。
    本脚本把「源稿 → 站点」变成可重复执行的单向推导：**改内容只改 book/，站点侧全量重建**。

真源（可用环境变量 BOOK_SRC_DIR 覆盖）：
    ~/WorkBuddy/2026-09-11-23-45-agent-column/book
        M<n>-<模块名>/<NN>-<讲标题>.md     讲稿
        00-前言与阅读指南.md                → 站点 index.md
    ~/WorkBuddy/2026-09-11-23-45-agent-column/_build
        lecture-map/m<n>/<NN>.svg           讲首导读图（每讲第 1 个 mermaid 块）
        mermaid-render/body/<NN>-<k>.svg    正文机制图（第 k+1 个块，k 从 1 起）
        architecture/*.svg                  架构图（源稿里以 <img class="arch-svg"> 引用）

转换规则（全部由既有产物反推确认，2026-09-26）：

  ① mermaid 代码块 → 内联图标签

         ```mermaid            →      （空行）
         flowchart TB …               <img class="mermaid-svg" src="/zh/book-assets/diag-NNNN.svg" alt="ALT" />
         ```

     替换串是 `"\\n" + 标签 + "\\n"`（原有尾随换行保留在外），这样前后各留一个空行、
     与历史产物逐字节一致。

  ② alt = mermaid 块的**上一行**原文，去掉行首 `> `、去掉所有 `*`，其余逐字保留，
     再做 HTML 属性转义（`&` `"` `<` `>`）。**反引号保留**（历史产物如此）。
     上一行可能是 `---`、`### 标题`、甚至代码围栏 ````` ``` `````——照实取，不猜。

  ③ diag 编号 = 全书按「模块号 → 讲号 → 块在讲内出现顺序」的全局序号，从 1 起。
     与讲号无关：这样编号是源稿的纯函数，增删讲次不会让编号产生歧义。

  ④ 源稿里 `<img class="arch-svg" src="../../_build/architecture/X.svg" …>` 的 src
     改写为 `/X.svg`（落到 site/public 根），并把 X.svg 补进 site/public/（缺才拷）。

  ⑤ 行内代码里含 `{{` 的会被 Vue 当插值 → 改写成 `<code v-pre>…</code>`（内容 HTML 转义）。

  ⑥ 全量对账：site/zh/book 与 site/public/zh/book-assets 下**不在本次产物清单里的一切**
     一律删除（目录也删）。只按文件名形状清理会漏掉「旧讲号 + 旧标题」这类孤儿。

用法：
    python3 site/.vitepress/gen_book_source.py --check   # 只比对，不落盘（CI / 交付前）
    python3 site/.vitepress/gen_book_source.py --write   # 落盘
    python3 site/.vitepress/gen_book_source.py --selftest
退出码：0 成功；1 有差异（--check）或断言失败；2 源稿目录缺失。
"""
import argparse
import html
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SITE_DIR = os.path.normpath(os.path.join(HERE, ".."))
REPO_ROOT = os.path.normpath(os.path.join(SITE_DIR, ".."))
OUT_BOOK = os.path.join(SITE_DIR, "zh", "book")
OUT_ASSETS = os.path.join(SITE_DIR, "public", "zh", "book-assets")
PUBLIC_DIR = os.path.join(SITE_DIR, "public")

DEFAULT_SRC = os.path.expanduser("~/WorkBuddy/2026-09-11-23-45-agent-column")
SRC = os.environ.get("BOOK_SRC_DIR", DEFAULT_SRC)
SRC_BOOK = os.path.join(SRC, "book")
SRC_BUILD = os.path.join(SRC, "_build")

PREFACE = "00-前言与阅读指南.md"
PREFACE_OUT = "index.md"

MERMAID = re.compile(r"^[ \t]*```mermaid\b.*?^[ \t]*```[ \t]*$", re.S | re.M)
ARCH_IMG = re.compile(r'<img class="arch-svg" src="[^"]*/([^"/]+\.svg)"')
CODE_MUSTACHE = re.compile(r"`([^`\n]*\{\{[^`\n]*)`")
MOD_DIR = re.compile(r"^M(\d+)-")
LEC_FILE = re.compile(r"^(\d{2})-(.+)$")

# 全书 mermaid 图块数的**口径快照**（164 = 73 导读图 + 91 正文机制图）。
# 单点定义：断言与提示都从这里取，避免「改了断言、忘了提示」两处各写一份而滞后。
# 2026-09-26 M8 补 27 张正文机制图后由 137 更新为 164（口径见源稿 book/README.md「图表索引」）。
EXPECTED_FIGS = 164

# 判据用：`{{` 是 Vue 插值起点，但 `<code v-pre>…</code>` 里的 `{{` 正是**被显式保护**的，
# 必须先剥掉保护片段再数，否则这个检查会把唯一一处正确处理报成故障（本判据首版就犯了这错）。
V_PRE_CODE = re.compile(r"<code v-pre>.*?</code>", re.S)
MUSTACHE_UNPROTECTED = re.compile(r"\{\{")
DIAG_REF = re.compile(r"/zh/book-assets/diag-\d{4}\.svg")

# 判据用：产出里**不许有指向站外的相对链接**。
# 踩过：第 54 讲引用 `docs/architecture.zh.md` 原文时，连原文的 markdown 相对链接
# （`../packages/bundle/base/README.zh.md`）一起抄进 blockquote，VitePress 把它当
# 站内路径解析 ⇒ `build error: 2 dead link(s) found`，站点直接构建不出来。
# 允许的目标：http(s) / `#` / `mailto:` / `data:` / 以 `/` 开头的站内绝对路径。
REL_HTML = re.compile(r'(?:href|src)\s*=\s*["\'](?!https?://|mailto:|data:|/|#)([^"\']+)["\']')
REL_MD = re.compile(r"\[[^\]]*\]\((?!https?://|mailto:|#|/)([^)\s]+)\)")
# 围栏内的内容是**逐字引用**（例如第 53 讲整段抄了 dsh 文档的一张表），不解析、不算问题。
FENCED = re.compile(r"^[ \t]*```.*?^[ \t]*```[ \t]*$", re.S | re.M)


def find_station_relative_links(body: str):
    """产出正文里所有「会被 VitePress 当站内路径解析」的链接目标（围栏内的不算）。"""
    visible = FENCED.sub("", body)
    return (REL_HTML.findall(visible) + REL_MD.findall(visible))


# 退化 alt：空白行、纯代码围栏（``` / ```java）、纯分隔线（--- / *** / ___）
FENCE_ONLY = re.compile(r"`{3,}\S*\s*$")
HR_ONLY = re.compile(r"([-*_])\1{2,}\s*$")


def is_degenerate_alt(line: str) -> bool:
    s = line.strip()
    return s == "" or bool(FENCE_ONLY.fullmatch(s)) or bool(HR_ONLY.fullmatch(s))


def alt_of(prev_line: str) -> str:
    """上一行 → alt 属性值（去 `> ` 前缀、去 `*`、HTML 属性转义）。

    `expandtabs` 不做：源稿里没有制表符，改了反而会与历史产物不一致。
    """
    s = re.sub(r"^\s*>\s?", "", prev_line)
    s = re.sub(r"\*+", "", s)
    return html.escape(s, quote=False).replace('"', "&quot;")


def count_bare_mustache(text: str) -> int:
    """数「会被 Vue 当插值」的 `{{` 处数 —— 先剥掉 <code v-pre> 保护片段。"""
    return len(MUSTACHE_UNPROTECTED.findall(V_PRE_CODE.sub("", text)))


def convert(text: str, src_svg_of_block, first_no: int = 1):
    """源稿正文 → 站点正文。`src_svg_of_block(i)` 返回第 i 个块（0 起）对应的 SVG 绝对路径。

    `first_no` 是本文件第 1 张图的**全局**编号（1 起）。编号必须跨文件连续——
    按文件重置会让所有讲都引用 diag-0001，164 个标签只指向 7 个资源（首版就犯了这个错，
    靠「引用集合必须等于资产集合」这条断言抓出来）。

    空白规则（**不是**逐字复刻历史产物——历史产物是多个一次性脚本版本的叠加，无法也不必复刻）：
        每个图标签**前后各保留源稿的空行数，但不低于 1 行**。
        下限 1 是硬要求：`<img>` 必须自成一段，markdown-it 才会把它当 HTML 块原样透传。
        （实测源稿 mermaid 块前的空行数分布是 0/1/2，块后恒为 1。）
    """
    matches = list(MERMAID.finditer(text))
    lines = text.split("\n")
    figures = []
    edits = []

    for m in matches:
        sl = text[: m.start()].count("\n")          # 起始围栏所在行
        el = text[: m.end()].count("\n")            # 结束围栏所在行
        if not lines[sl].strip().startswith("```mermaid"):
            raise AssertionError("第 %d 行不是 mermaid 围栏：%r" % (sl + 1, lines[sl]))
        if lines[el].strip() != "```":
            raise AssertionError("第 %d 行不是结束围栏：%r" % (el + 1, lines[el]))

        prev = text[: m.start()].rstrip("\n").split("\n")[-1]
        alt_src = prev
        if is_degenerate_alt(prev):
            # 上一行是空行 / 代码围栏 / 分隔线时它不描述任何东西，取下一非空行（通常是图注）
            nxt = el + 1
            while nxt < len(lines) and lines[nxt].strip() == "":
                nxt += 1
            if nxt < len(lines) and not is_degenerate_alt(lines[nxt]):
                alt_src = lines[nxt]
        svg = src_svg_of_block(len(figures))
        no = first_no + len(figures)
        figures.append(svg)
        tag = ('<img class="mermaid-svg" src="/zh/book-assets/diag-%04d.svg" alt="%s" />'
               % (no, alt_of(alt_src)))

        b = 0
        while sl - 1 - b >= 0 and lines[sl - 1 - b].strip() == "":
            b += 1
        a = 0
        while el + 1 + a < len(lines) and lines[el + 1 + a].strip() == "":
            a += 1

        lo = sl - b
        hi = el + a                                  # 闭区间
        lead = [] if lo == 0 else [""] * max(b, 1)   # 文件开头的块不再补空行
        repl = lead + [tag] + [""] * max(a, 1)
        edits.append((lo, hi, repl))

    # 逆序应用，避免前面的编辑挪动后面的行号
    for lo, hi, repl in reversed(edits):
        lines[lo:hi + 1] = repl
    out = "\n".join(lines)

    # 源稿里的 arch 图是相对路径；站点侧落在 public 根
    out = ARCH_IMG.sub(lambda m: '<img class="arch-svg" src="/%s"' % m.group(1), out)
    # Vue 会把 {{ … }} 当插值；行内代码要显式 v-pre
    out = CODE_MUSTACHE.sub(
        lambda m: "<code v-pre>%s</code>" % html.escape(m.group(1), quote=False), out)
    return out, figures


def plan():
    """→ (输出清单 {相对路径: (内容, 图源列表)}, 全局图序号 {diag 名: 源 svg 路径})"""
    mods = []
    for entry in sorted(os.listdir(SRC_BOOK)):
        full = os.path.join(SRC_BOOK, entry)
        if os.path.isdir(full) and MOD_DIR.match(entry):
            mods.append(entry)
    mods.sort(key=lambda e: int(MOD_DIR.match(e).group(1)))

    outputs = {}
    assets = {}
    diag_n = 0
    stats = []

    for mod in mods:
        tag = "m%s" % MOD_DIR.match(mod).group(1)          # M12-… → m12
        for fn in sorted(os.listdir(os.path.join(SRC_BOOK, mod))):
            m = LEC_FILE.match(fn)
            if not fn.endswith(".md") or not m:
                continue
            nn = m.group(1)
            src_path = os.path.join(SRC_BOOK, mod, fn)
            text = open(src_path, encoding="utf-8").read()

            def src_svg_of_block(i, _tag=tag, _nn=nn):
                if i == 0:
                    return os.path.join(SRC_BUILD, "lecture-map", _tag, "%s.svg" % _nn)
                return os.path.join(SRC_BUILD, "mermaid-render", "body",
                                    "%s-%d.svg" % (_nn, i))

            body, figs = convert(text, src_svg_of_block, diag_n + 1)
            rel = "%s/%s" % (mod, fn)
            outputs[rel] = (body, figs)

            for svg in figs:
                diag_n += 1
                assets["diag-%04d.svg" % diag_n] = svg
            stats.append((rel, len(figs)))

    # 前言 → index.md
    pre = open(os.path.join(SRC_BOOK, PREFACE), encoding="utf-8").read()
    pre_body, pre_figs = convert(pre, lambda i: None)
    assert not pre_figs, "前言里不应有 mermaid 块（若有请改用 index 的图源规则）"
    outputs[PREFACE_OUT] = (pre_body, pre_figs)

    return outputs, assets, stats


def reconcile_dir(path):
    """删掉 path 下所有文件与子目录（保留 path 本身）。"""
    if not os.path.isdir(path):
        return
    for entry in os.listdir(path):
        p = os.path.join(path, entry)
        if os.path.isdir(p):
            shutil.rmtree(p)
        else:
            os.remove(p)


def selftest():
    ok = True

    # ① alt 规则：必报样本（去 `> ` 去 `*`、保留反引号、转义引号）
    got = alt_of('> 本讲是全书**唯一**一讲"以跑通为目标"的课。')
    want = '本讲是全书唯一一讲&quot;以跑通为目标&quot;的课。'
    print("alt 规则样本 → %r（期望 %r）→ %s" % (got, want, "通过" if got == want else "失败"))
    ok &= got == want

    got2 = alt_of("工具把 `AgentTool` 画成对象图：")
    print("alt 保留反引号 → %r → %s" % (got2, "通过" if got2 == "工具把 `AgentTool` 画成对象图：" else "失败"))
    ok &= got2 == "工具把 `AgentTool` 画成对象图："

    got3 = alt_of("---")
    print("alt_of 纯函数行为（分隔线原样透传，退化判定在 convert 里）→ %r → %s"
          % (got3, "通过" if got3 == "---" else "失败"))
    ok &= got3 == "---"

    # ② 空白规则：源稿块前 0 空行 → 产物前 1 空行（下限 1）；块后 1 空行 → 产物后 1 空行
    src = '> 引导语\n```mermaid\nflowchart TB\n  A-->B\n```\n\n> **图 01-0**　地图\n'
    out, figs = convert(src, lambda i: "/tmp/x.svg")
    want_out = ('> 引导语\n\n<img class="mermaid-svg" src="/zh/book-assets/diag-0001.svg" '
                'alt="引导语" />\n\n> **图 01-0**　地图\n')
    print("mermaid 替换空白 → %s" % ("通过" if out == want_out else "失败"))
    if out != want_out:
        print("  实际 %r\n  期望 %r" % (out, want_out))
    ok &= out == want_out
    ok &= len(figs) == 1

    # ②b 源稿块前已有 2 空行 → 产物保留 2 空行（不归一）
    src2 = '上文\n\n\n```mermaid\nflowchart TB\n  A-->B\n```\n\n下一条\n'
    out2b, _ = convert(src2, lambda i: "/tmp/x.svg")
    want2b = ('上文\n\n\n<img class="mermaid-svg" src="/zh/book-assets/diag-0001.svg" '
              'alt="上文" />\n\n下一条\n')
    print("块前 2 空行保留 → %s" % ("通过" if out2b == want2b else "失败"))
    if out2b != want2b:
        print("  实际 %r\n  期望 %r" % (out2b, want2b))
    ok &= out2b == want2b

    # ③ arch 图相对路径改写
    a, _ = convert('<img class="arch-svg" src="../../_build/architecture/x.svg" alt="a">', lambda i: None)
    print("arch 路径改写 → %s" % ("通过" if 'src="/x.svg"' in a else "失败"))
    ok &= 'src="/x.svg"' in a

    # ④ `{{` 行内代码 → <code v-pre>
    c, _ = convert("支持 `{{> common_header}}` 小语法", lambda i: None)
    print("{{ 行内代码 → %s" % ("通过" if "<code v-pre>{{&gt; common_header}}</code>" in c else "失败"))
    ok &= "<code v-pre>{{&gt; common_header}}</code>" in c

    # ⑤ 幂等：对已转换过的文本再转一次，不应再产生新图（mermaid 已不存在）
    again, figs2 = convert(out, lambda i: None)
    print("幂等复跑新增图数 = %d，期望 0 → %s" % (len(figs2), "通过" if not figs2 else "失败"))
    ok &= not figs2

    # ⑥ `{{` 判据：必报（裸插值）+ 必放过（v-pre 保护）
    n_bad = count_bare_mustache("模板里支持 {{> header}} 这种语法")
    print("裸 `{{` 待报数 = %d，期望 1 → %s" % (n_bad, "通过" if n_bad == 1 else "失败"))
    ok &= n_bad == 1
    n_ok = count_bare_mustache("<code v-pre>{{&gt; common_header}}</code>")
    print("v-pre 保护片段待报数 = %d，期望 0 → %s" % (n_ok, "通过" if n_ok == 0 else "失败"))
    ok &= n_ok == 0

    # ⑦ 退化 alt 兜底：上一行是分隔线 → 取下一非空行（图注）；上一行有意义时不受影响
    src3 = ('> 视角切换声明\n\n---\n\n```mermaid\nflowchart TB\n  A-->B\n```\n\n'
            '> **图 30-0**　本讲地图：分层按依赖方向切。\n')
    out3, _ = convert(src3, lambda i: "/tmp/x.svg")
    print("退化 alt 兜底 → %s"
          % ("通过" if 'alt="图 30-0　本讲地图：分层按依赖方向切。"' in out3 else "失败"))
    if 'alt="图 30-0　本讲地图：分层按依赖方向切。"' not in out3:
        print("  实际 %r" % out3)
    ok &= 'alt="图 30-0　本讲地图：分层按依赖方向切。"' in out3
    for s in ("---", "```", "```java", "***", "   "):
        ok &= is_degenerate_alt(s)
    for s in ("> 引导语", "### 3.1 小节标题", "回上一行正文：", "图 30-0 的说明"):
        ok &= not is_degenerate_alt(s)
    print("退化判定（5 必报 + 4 必放过）→ %s" % ("通过" if ok else "失败"))

    # ⑧ 编号必须**跨文件连续**（首版按文件重置 ⇒ 164 个标签只指向 7 个资源）
    _b1, f1 = convert("```mermaid\nA-->B\n```\n", lambda i: "/tmp/a1.svg", 1)
    b2, f2 = convert("```mermaid\nA-->B\n```\n\n```mermaid\nC-->D\n```\n",
                     lambda i: "/tmp/a2.svg", 1 + len(f1))
    numbering_ok = ("diag-0002.svg" in b2 and "diag-0003.svg" in b2
                    and "diag-0001.svg" not in b2 and len(f2) == 2)
    print("跨文件编号连续 → %s" % ("通过" if numbering_ok else "失败"))
    if not numbering_ok:
        print("  实际 %r" % b2)
    ok &= numbering_ok

    # ⑨ 站外相对链接判据（第 54 讲那次 `build error: 2 dead link(s)` 就是它漏掉的）
    #    必报 2 个：blockquote 里抄原文的 markdown 相对链接 + HTML img 的相对 src
    bad = [
        "> [`dsh-base`](../packages/bundle/base/README.zh.md) 是共享第一层。",
        '<img class="arch-svg" src="../../_build/architecture/x.svg" alt="a">',
    ]
    n_bad = sum(len(find_station_relative_links(s)) for s in bad)
    print("站外相对链接待报数 = %d，期望 2 → %s" % (n_bad, "通过" if n_bad == 2 else "失败"))
    ok &= n_bad == 2

    #    必放过 3 类：外链 / 站内锚点 / 站内绝对路径，以及**围栏内的逐字引用**（第 53 讲那张表）
    good = [
        "见 [Anthropic 文档](https://docs.anthropic.com/x)。",
        "见 [本讲](#anchor)，图见 ![图](/zh/book-assets/diag-0001.svg)。",
        "```markdown\n| `ctx.shell` | `seam` | [`shell`](../packages/shell/shell) |\n```\n",
    ]
    n_good = sum(len(find_station_relative_links(s)) for s in good)
    print("站外相对链接必放过 = %d，期望 0 → %s" % (n_good, "通过" if n_good == 0 else "失败"))
    if n_good:
        for s in good:
            print("  误报：%s → %s" % (s[:30], find_station_relative_links(s)))
    ok &= n_good == 0

    print("\n自测%s" % ("全部通过" if ok else "未通过"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--write", action="store_true", help="落盘")
    g.add_argument("--check", action="store_true", help="只比对，不落盘")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not os.path.isdir(SRC_BOOK):
        print("源稿目录不存在：%s（可用 BOOK_SRC_DIR 覆盖）" % SRC_BOOK, file=sys.stderr)
        return 2

    outputs, assets, stats = plan()

    # ---- 断言：结构 ----
    lec = [r for r in outputs if r != PREFACE_OUT]
    mods = sorted({r.split("/")[0] for r in lec}, key=lambda e: int(MOD_DIR.match(e).group(1)))
    n_fig = sum(len(f) for _, f in outputs.values())
    problems = []
    if len(lec) != 73:
        problems.append("讲数 %d ≠ 73" % len(lec))
    if len(mods) != 11:
        problems.append("模块数 %d ≠ 11" % len(mods))
    if n_fig != EXPECTED_FIGS:
        problems.append("mermaid 图 %d ≠ %d" % (n_fig, EXPECTED_FIGS))
    if len(assets) != n_fig:
        problems.append("图资产 %d ≠ 图块 %d" % (len(assets), n_fig))

    # ★ 引用集合必须与资产集合逐一相等：编号一旦「按文件重置」，
    #   资产数照样是 164，但 164 个标签会全指向 7 个资源 —— 只有这条判据拦得住。
    refs = DIAG_REF.findall("".join(b for b, _ in outputs.values()))
    if len(refs) != n_fig:
        problems.append("图标签 %d ≠ 图块 %d" % (len(refs), n_fig))
    if len(set(refs)) != len(refs):
        dup = sorted({r for r in refs if refs.count(r) > 1})[:5]
        problems.append("图号被重复引用（编号未全局递增）：%s" % dup)
    if set(refs) != {"/zh/book-assets/" + k for k in assets}:
        problems.append("引用的图号集合与生成的资产集合不一致")

    # ★ 产出里**不许有指向站外的相对链接**（见 find_station_relative_links 的说明）。
    #   围栏内的逐字引用不算——剥围栏后的检查在函数里做。
    rel = []
    for name, (body, _figs) in outputs.items():
        for target in find_station_relative_links(body):
            rel.append("%s → %s" % (name, target))
    if rel:
        problems.append(
            "产出里有站外相对链接 %d 处（VitePress 会当站内路径解析并构建失败）：%s"
            % (len(rel), rel[:3]))

    for name, svg in assets.items():
        if not svg or not os.path.exists(svg):
            problems.append("图源缺失：%s ← %s" % (name, svg))

    # ---- 断言：产物自检（这些是「读者会看到源码」类静默故障的正面判据）----
    try:
        sys.path.insert(0, HERE)
        from fix_book_figures import TAG_RE, needs_fix
    except Exception as e:  # pragma: no cover
        TAG_RE = needs_fix = None
        problems.append("无法导入 fix_book_figures（alt 判据不可用）：%s" % e)

    mustache = unsafe_alt = 0
    for rel, (body, _) in outputs.items():
        mustache += count_bare_mustache(body)
        if TAG_RE:
            unsafe_alt += sum(1 for m in TAG_RE.finditer(body) if needs_fix(m.group("alt")))
    if mustache:
        problems.append("产物里有 %d 处未加 v-pre 的裸 `{{`（Vue 会当插值）" % mustache)
    if unsafe_alt:
        problems.append("产物里有 %d 个图标签 alt 未做属性转义" % unsafe_alt)

    if problems:
        print("失败 %d 项：" % len(problems), file=sys.stderr)
        for p in problems:
            print("  ✗ %s" % p, file=sys.stderr)
        return 1

    if not args.quiet:
        print("源稿 %s" % SRC_BOOK)
        print("计划产物：%d 讲 + 前言 → %s；模块 %d 个；mermaid 图 %d 张 → book-assets/%d 个文件"
              % (len(lec), PREFACE_OUT, len(mods), n_fig, len(assets)))
        print("模块：" + " ".join("%s(%d 讲/%d 图)"
                                 % (m, sum(1 for r in lec if r.split('/')[0] == m),
                                    sum(len(outputs[r][1]) for r in lec
                                        if r.split('/')[0] == m))
                                 for m in mods))

    # ---- 与现有产物比对 ----
    def existing_md():
        got = {}
        if os.path.isdir(OUT_BOOK):
            for root, dirs, files in os.walk(OUT_BOOK):
                for fn in files:
                    p = os.path.join(root, fn)
                    got[os.path.relpath(p, OUT_BOOK).replace(os.sep, "/")] = open(p, encoding="utf-8").read()
        return got

    cur = existing_md()
    changed = sorted(r for r in outputs if cur.get(r) != outputs[r][0])
    stale = sorted(set(cur) - set(outputs))
    try:
        have_assets = {f for f in os.listdir(OUT_ASSETS) if f.endswith(".svg")}
    except OSError:
        have_assets = set()
    stale_assets = sorted(have_assets - set(assets))
    changed_assets = sorted(n for n in assets
                            if n in have_assets and open(os.path.join(OUT_ASSETS, n), "rb").read()
                            != open(assets[n], "rb").read())

    if not args.quiet:
        print("比对现有产物：md 变更 %d / 孤儿 %d；图资产 变更 %d / 孤儿 %d"
              % (len(changed), len(stale), len(changed_assets), len(stale_assets)))
        for r in changed[:15]:
            print("   ~ %s" % r)
        if len(changed) > 15:
            print("   … 其余 %d 个" % (len(changed) - 15))
        for r in stale[:10]:
            print("   - 删除 %s" % r)
        for r in stale_assets[:10]:
            print("   - 删除资产 %s" % r)

    if args.check:
        if changed or stale or stale_assets or changed_assets:
            print("\n--check：站点源与源稿不一致（跑 --write 重建）")
            return 1
        print("\n--check：站点源与源稿一致 ✓")
        return 0

    if not args.write:
        print("\n（未落盘；加 --write 生效）")
        return 0

    # ---- 落盘：先全量清掉，再从清单写回（保证无孤儿）----
    reconcile_dir(OUT_BOOK)
    os.makedirs(OUT_ASSETS, exist_ok=True)
    for name in list(os.listdir(OUT_ASSETS)):
        p = os.path.join(OUT_ASSETS, name)
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)

    for rel, (body, _) in outputs.items():
        dst = os.path.join(OUT_BOOK, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "w", encoding="utf-8").write(body)
    for name, svg in assets.items():
        shutil.copyfile(svg, os.path.join(OUT_ASSETS, name))

    # arch 图补齐（只补缺，不动 site/public 下其余文件——那里还归站点其他页面用）
    for rel, (body, _) in outputs.items():
        for m in ARCH_IMG.finditer(body):
            name = m.group(1)
            target = os.path.join(PUBLIC_DIR, name)
            if not os.path.exists(target):
                src = os.path.join(SRC_BUILD, "architecture", name)
                if os.path.exists(src):
                    shutil.copyfile(src, target)
                    print("   补入 arch 资产 %s" % name)
                else:
                    print("   ！缺 arch 资产 %s（源稿与 _build 都没有）" % name, file=sys.stderr)

    # 落盘后复检
    cur2 = existing_md()
    left = sorted(set(cur2) - set(outputs)) + sorted(r for r in outputs if cur2.get(r) != outputs[r][0])
    assets2 = {f for f in os.listdir(OUT_ASSETS) if f.endswith(".svg")}
    print("\n已写入：md %d 个 → %s" % (len(outputs), OUT_BOOK))
    print("已写入：图资产 %d 个 → %s" % (len(assets), OUT_ASSETS))
    print("复检：残留孤儿/不一致 %d 项；资产 %d 个（期望 %d）"
          % (len(left), len(assets2), len(assets)))
    return 0 if not left and len(assets2) == len(assets) else 1


if __name__ == "__main__":
    sys.exit(main())

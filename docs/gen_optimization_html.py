#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 docs/生态调研与优化方案-<日期>.md 渲染为同名单文件 HTML（零外部依赖，可离线看）。

为什么要有这个脚本：
    在此之前 HTML 是「一次性生成、无人可复现」的产物。方案文档一旦更新，
    HTML 会静默停在旧版——读者拿到的可能是与结论不一致的一版，而且没有任何提示。
    把样式与渲染固化在这里后，`python3 docs/gen_optimization_html.py <日期>` 即可重建。

用法：
    python3 docs/gen_optimization_html.py 2026-09-27

依赖：markdown-it-py（本机位于 ~/.workbuddy/binaries/python/envs/default）。
"""
import os
import sys

from markdown_it import MarkdownIt

STYLE = """<style>
:root{--fg:#1f2328;--muted:#59636e;--line:#d8dee4;--bg:#ffffff;--soft:#f6f8fa;
--link:#0969da;--code-bg:#f6f8fa;--th:#f6f8fa;}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:16px/1.75 -apple-system,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;}
main{max-width:980px;margin:0 auto;padding:48px 32px 96px;}
h1{font-size:30px;line-height:1.35;margin:0 0 8px;padding-bottom:12px;border-bottom:2px solid var(--line);}
h2{font-size:23px;margin:44px 0 14px;padding-bottom:8px;border-bottom:1px solid var(--line);}
h3{font-size:19px;margin:30px 0 10px;}
h4{font-size:16px;margin:22px 0 8px;color:var(--muted);}
p{margin:12px 0;}
a{color:var(--link);text-decoration:none}
a:hover{text-decoration:underline}
code{background:var(--code-bg);border:1px solid var(--line);border-radius:5px;
padding:1px 5px;font-size:13.5px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
pre{background:var(--soft);border:1px solid var(--line);border-radius:8px;padding:14px 16px;overflow:auto;}
pre code{background:none;border:none;padding:0;font-size:13px;line-height:1.6;}
blockquote{margin:16px 0;padding:12px 18px;background:var(--soft);
border-left:4px solid #b9c4d0;border-radius:0 6px 6px 0;color:#3a4450;}
blockquote p{margin:6px 0;}
table{border-collapse:collapse;width:100%;margin:16px 0;font-size:14px;display:block;overflow-x:auto;}
th,td{border:1px solid var(--line);padding:8px 12px;text-align:left;vertical-align:top;}
th{background:var(--th);font-weight:600;}
tbody tr:nth-child(even){background:#fbfcfd;}
ul,ol{padding-left:26px;margin:12px 0;}
li{margin:6px 0;}
hr{border:none;border-top:1px solid var(--line);margin:36px 0;}
strong{font-weight:650;}
</style>"""

PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title>
{style}
</head><body>
<main>
{body}
</main></body></html>
"""


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    day = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    md_path = os.path.join(here, "生态调研与优化方案-%s.md" % day)
    html_path = os.path.join(here, "生态调研与优化方案-%s.html" % day)
    if not os.path.isfile(md_path):
        print("源文件不存在：%s" % md_path)
        return 1

    md = open(md_path, encoding="utf-8").read()
    renderer = MarkdownIt("commonmark").enable("table")
    body = renderer.render(md)

    # 标题取 H1 文本；行内标记需剥离，否则 <title> 里会出现字面星号
    first = ""
    for line in md.split("\n"):
        if line.startswith("# "):
            first = line[2:].strip().replace("`", "").replace("**", "")
            break

    html = PAGE.format(title=first, style=STYLE, body=body)
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(html)
    print("渲染完成：%s（md %d 字节 -> html %d 字节）"
          % (os.path.basename(html_path), len(md.encode("utf-8")), len(html.encode("utf-8"))))
    return 0


if __name__ == "__main__":
    sys.exit(main())

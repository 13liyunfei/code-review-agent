#!/usr/bin/env python3
"""量化：专栏插图在 688px 正文栏里的等效字号。

SVG 形态是 <svg width="100%" style="max-width: Wpx" viewBox="0 0 VW VH">，
width=100% ⇒ 实际渲染宽 = min(容器宽, max-width)。viewBox 内容按比例缩放，
所以图里 16px 的字在页面上实际只有 16 * 渲染宽 / viewBox宽 像素。
"""
import glob, os, re, statistics as st

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "public", "zh", "book-assets")
CONTAINER = 688  # VitePress 正文栏实测渲染宽（改主题宽度后需同步此值）

rows = []
for p in sorted(glob.glob(os.path.join(ASSETS, "*.svg"))):
    head = open(p, encoding="utf-8").read(4000)
    vb = re.search(r'viewBox="[-\d.]+ [-\d.]+ ([\d.]+) ([\d.]+)"', head)
    mw = re.search(r'max-width:\s*([\d.]+)px', head)
    fs = re.search(r'font-size:\s*([\d.]+)px', head)
    if not vb:
        continue
    vw = float(vb.group(1)); vh = float(vb.group(2))
    maxw = float(mw.group(1)) if mw else vw
    base = float(fs.group(1)) if fs else 16.0
    render = min(CONTAINER, maxw)
    scale = render / vw
    eff = base * scale
    rows.append(dict(f=os.path.basename(p), vw=vw, vh=vh, maxw=maxw, render=render,
                     scale=scale, eff=eff, overflow=vh * scale))

effs = sorted(r["eff"] for r in rows)
print(f"图 {len(rows)} 张；容器宽 {CONTAINER}px")
print(f"等效字号  最小={effs[0]:.2f}  P25={effs[len(effs)//4]:.2f}  中位={st.median(effs):.2f}  P75={effs[len(effs)*3//4]:.2f}  最大={effs[-1]:.2f}")
print(f"缩放比    中位={st.median([r['scale'] for r in rows]):.3f}  最小={min(r['scale'] for r in rows):.3f}")
print(f"viewBox 宽 中位={st.median([r['vw'] for r in rows]):.0f} 最大={max(r['vw'] for r in rows):.0f}")

for lo, hi in [(0, 6), (6, 8), (8, 10), (10, 12), (12, 99)]:
    n = len([e for e in effs if lo <= e < hi])
    print(f"  等效字号 {lo:>2}-{hi:<2}px : {n:>3} 张 {'█' * n}")

print("\n最差的 12 张（内容宽度 / 等效字号）：")
for r in sorted(rows, key=lambda r: r["eff"])[:12]:
    print(f"  {r['f']}  viewBox {r['vw']:.0f}×{r['vh']:.0f}  max-width {r['maxw']:.0f}  渲染 {r['render']:.0f}  缩放 {r['scale']:.3f}  等效字号 {r['eff']:.2f}px  显示高 {r['overflow']:.0f}px")

narrow = [r for r in rows if r["maxw"] < CONTAINER]
print(f"\nmax-width 小于容器（即会被放大或原样）的图 = {len(narrow)} 张")

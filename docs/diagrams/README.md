# 业务流程图集 · 源码与构建

- `*.mmd` —— mermaid 源码。**改图改这里**。
- `_build/` —— 构建脚本（渲染 + 内联生成 HTML）。
- `../business-flow.html` —— 最终产物，SVG 全部内联、零外部依赖、可离线查看。

## 重建流程

```bash
cd _build
npm i @mermaid-js/mermaid-cli        # 一次性，需 Chromium
node render.mjs                      # 渲染 SVG 到 .render/，并打印每张图尺寸
node build-doc.mjs                   # 内联 SVG 生成 ../../business-flow.html
```

`render.mjs` 会打印每张图的像素尺寸并标记是否超界：

- 高度 > 1700px 或宽度 > 1500px 会打 ⚠️，此时应拆图或精简节点文案。
- 节点文案控制在 2 行内、每行 ≤ 16 个汉字，超出会让节点变高、图变长。

## 两个必须遵守的约束

1. **mermaid 标签里禁止出现尖括号**。写 `List<Finding>` 会被当成未知 HTML 标签吞掉，只剩 `List`。改用「Finding 列表」这类无尖括号写法。

2. **不要用编辑器直接改 `business-flow.html` 或用 Write 工具写含 HTML 的文件**。编辑器会给标签注入 `data-page-node-id` 属性（连 `<br/>` 都会被改），直接破坏 mermaid 源码与内联 SVG。HTML 一律由 `build-doc.mjs` 生成。

## 内联 SVG 的三个坑（build-doc.mjs 已处理）

1. mermaid 把根 id、样式选择器前缀、箭头 marker id 全部写死为 `my-svg`。多图内联时必须统一替换成唯一 id，否则：只改根 id 会让整套配色/描边/连线样式失效（只剩默认黑线框）；不改 marker id 会让各图的箭头标记互相撞车。
2. `htmlLabels` 必须设为 `false`（**顶层配置项**，不是 `flowchart.htmlLabels`）。为 true 时节点文字用 `foreignObject` + HTML div 渲染，框宽按渲染机的字体度量算死，换台机器/浏览器字体不同，文字就会撑破边框。设 false 后改用原生 SVG text，位置在渲染时固定，跨浏览器一致。
3. mmdc 默认输出 `width="100%"`，在窄容器里会把整图压成小字。`build-doc.mjs` 按 viewBox 还原为自然像素尺寸，宽图交给容器横向滚动。

# 业务流程图集 · 源码与构建

- `*.mmd` —— mermaid 源码。**改图改这里**。
- `manifest.json` —— 章节元数据：标题、每图说明、源码锚点表、提示/警示块。
- `_build/` —— 构建脚本（渲染 → 内联 → 验收），来自 skill `mermaid-diagram-book`。
- `../business-flow.html` —— 最终产物，SVG 全部内联、零外部依赖、可离线查看。

## 重建流程

```bash
cd _build
npm i @mermaid-js/mermaid-cli        # 一次性，需 Chromium；已有安装可设 MMDC_PATH
node render.mjs                      # 渲染 SVG 到 .render/，打印每张图尺寸
node build-doc.mjs                   # 内联生成 ../../business-flow.html
node verify.mjs ../../business-flow.html   # 验收（需 puppeteer，可设 PUPPETEER_MODULE_PATH）
```

`render.mjs` 会打印每张图的像素尺寸并标记是否超界：

- 高度 > 1700px 或宽度 > 1500px 会打 ⚠️，此时应拆图或精简节点文案。
- 节点文案控制在 2 行内、每行 ≤ 16 个汉字，超出会让节点变高、图变长。

`verify.mjs` 全绿才算交付：样式生效、文字溢出 0、悬空引用 0、svg id 唯一、
放大可开且 Esc 可关、无 JS 报错、`my-svg` 残留 0、`data-page-node-id` 注入 0、
目录（sticky 生效 + 滚动高亮全对 + 触底高亮末章 + 窄屏折回）。
任一不满足即以非 0 退出码结束。

## 目录

产物左侧是常驻目录（宽屏 sticky 侧栏，≤1080px 折回顶部卡片网格），滚动时自动高亮
当前章节。目录项与 `manifest.json` 的 `sections` 一一对应，加章节只改 manifest。

- 判定当前章节用「视口顶部下方 140px 的最后一条」，**不用 IntersectionObserver**：
  各章高度差异极大（含 1500px 长图），threshold 很难同时适配，边界会来回跳。
- 触底强制高亮末章：末章较短时其顶部永远越不过判定线。

## 两条必须遵守的约束

1. **mermaid 标签里禁止出现尖括号**。写 `List<Finding>` 会被当成未知 HTML 标签吞掉，只剩 `List`。改用「Finding 列表」这类无尖括号写法。

2. **不要用编辑器直接改产物 HTML，也不要用 Write 写含 HTML 的文件**。
   编辑器会给每个标签注入 `data-page-node-id`。实测本图集一度被塞进 **4453 处 / 191KB**
   垃圾属性，且因覆盖写后未 fsync，`statSync` 读到的还是旧大小，问题被掩盖。
   HTML 一律由 `build-doc.mjs` 生成；`.mmd` 源码的注入由 `render.mjs` 在渲染前清理。

## 五个必踩的坑（脚本已处理，改脚本时别弄丢）

1. **`my-svg` 必须全局替换**。mermaid 把根 id、`<style>` 里的选择器前缀、
   箭头 `<marker id>` 全部写死成 `my-svg`。只改根 id 不改 style 前缀 →
   整套配色/描边/连线样式失效，只剩默认黑线框（用户反馈的「图好丑」真因）；
   不改 marker id → 各图箭头标记互相撞车。

2. **`htmlLabels: false` 必须写在 config 顶层**。默认 true 时节点文字用
   `foreignObject` + HTML div 渲染，框宽按**渲染机**的字体度量算死，
   换个浏览器字体不同就撑破边框（「文字显示不全」真因）。
   写在 `flowchart.htmlLabels` 下会被 v11 静默忽略。

3. **mmdc 默认输出 `width="100%"`**，窄容器里整图被压成小字。
   `build-doc.mjs` 按 viewBox 还原自然尺寸，`height` 必须显式给出，宽图交给容器横向滚动。

4. **两栏布局下 grid 子项要显式 `min-width: 0`**。grid 子项默认 `min-width: auto`，
   会被超宽内联 SVG 顶开，横向溢出落到整页而非 `.diagram` 的滚动条上
   （实测 900px 视口下正文被撑到 1189px）。media query 里的 `1fr` 同理，
   要写 `minmax(0, 1fr)`。

5. **锚点表里的长文件路径会让整页横向溢出**。需 `table-layout: fixed` +
   `overflow-wrap: anywhere`。

另：**产物写完必须 fsync 再统计大小**。macOS APFS 上覆盖写大文件后立刻 `statSync`
会拿到过期 inode size（实测报 901957 而 ls 显示 1093436），
会让紧随其后跑的验收脚本读到不完整内容。

## 通用化

这套脚本与本项目无关，可复制到任何需要图集交付的场景。
通用版维护在 skill `mermaid-diagram-book`（`~/.workbuddy/skills/`），
章节内容全部外置到 `manifest.json`，改文案不用动脚本。

/**
 * 内联步骤：把 .render/*.svg 内联进一个零外部依赖、可离线查看的 HTML 图集。
 *
 * 用法：node build-doc.mjs
 * 输入：../manifest.json（章节元数据） + .render/dNN.svg（render.mjs 产物）
 * 输出：manifest.output 指定的 HTML
 *
 * 这里处理了三个会让成品「又丑又错」的坑，见内联注释 [坑1] [坑2] [坑3]。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..');
const OUT_DIR = path.join(HERE, '.render');

const manifest = JSON.parse(fs.readFileSync(path.join(SRC, 'manifest.json'), 'utf8'));
const OUT = path.resolve(SRC, manifest.output || '../diagrams.html');
const order = JSON.parse(fs.readFileSync(path.join(OUT_DIR, 'order.json'), 'utf8'));
const byName = new Map(order.map(o => [o.name, o.file]));

function prepareSvg(name, idx) {
  const file = byName.get(name);
  if (!file) throw new Error(`manifest 引用了不存在的图：${name}（已渲染：${order.map(o => o.name).join(', ')}）`);
  let svg = fs.readFileSync(path.join(OUT_DIR, `${file}.svg`), 'utf8');
  svg = svg.replace(/<\?xml[^>]*\?>\s*/g, '').replace(/<!DOCTYPE[^>]*>\s*/g, '');
  const vb = (svg.match(/viewBox="([^"]+)"/) || [])[1] || '0 0 800 600';
  const [, , vw, vh] = vb.split(/\s+/).map(Number);
  const id = `flow-${idx}`;

  // [坑1] mermaid 把根 id、<style> 里的选择器前缀、箭头 <marker id="my-svg_..."> 全部写死成 my-svg。
  // 只改根 id 而不改 style 里的 #my-svg 前缀 → 整套配色/描边/连线样式全部失效，只剩默认黑细线框；
  // 不改 marker id → 多张图的箭头标记互相撞车。必须全局替换，一次搞定。
  svg = svg.replace(/my-svg/g, id);

  // [坑3] mmdc 默认输出 width="100%"，窄容器里整图被压成小字。按 viewBox 还原自然像素尺寸，
  // 宽图交给容器横向滚动；height 必须显式给出，否则 SVG 按 viewBox 比例自行拉伸。
  svg = svg.replace(/<svg([^>]*)>/, (m, attrs) => {
    const a = attrs
      .replace(/\swidth="[^"]*"/, ` width="${Math.ceil(vw)}"`)
      .replace(/\sstyle="[^"]*"/, ' style="max-width:none;background-color:transparent;"');
    return `<svg${a} height="${Math.ceil(vh)}">`;
  });
  return { svg, w: Math.ceil(vw), h: Math.ceil(vh) };
}

const blocks = manifest.sections.map((s, i) => {
  const { svg, w, h } = prepareSvg(s.file, i + 1);
  const anchors = s.anchors && s.anchors.length
    ? `\n  <table class="anchor">\n    <caption>源码锚点</caption>\n`
      + s.anchors.map(([k, v]) => `    <tr><td>${k}</td><td>${v}</td></tr>`).join('\n')
      + `\n  </table>`
    : '';
  return `<section id="${s.id}">
  <h2><span class="tag">${s.tag || s.id}</span>${s.title}</h2>
  <p class="sub">${s.sub || ''}</p>
  <figure class="chart">
    <div class="diagram">${svg}</div>
    <figcaption>点击放大 · 原图 ${w} × ${h}</figcaption>
  </figure>${anchors}
  ${s.info ? `\n  <div class="info">${s.info}</div>` : ''}
  ${s.note ? `\n  <div class="note">${s.note}</div>` : ''}
</section>`;
});

const toc = manifest.sections.map(s =>
  `    <li><a href="#${s.id}"><span class="tag">${s.tag || s.id}</span>${s.title}</a></li>`).join('\n');
const meta = (manifest.meta || []).join('<br>\n    ');

const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${manifest.title}</title>
<style>
  :root {
    --bg: #ffffff; --ink: #1f2933; --ink-2: #52606d; --line: #e4e7eb;
    --accent: #2c7d2c; --accent-soft: #f0f6f0;
    --blue: #1d4ed8; --blue-soft: #eef2ff;
    --amber: #b45309; --amber-soft: #fff7ed;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--ink);
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif;
    line-height: 1.7; font-size: 15px;
  }
  .wrap { max-width: 1180px; margin: 0 auto; padding: 48px 32px 96px; }
  header.hero { border-bottom: 2px solid var(--accent); padding-bottom: 24px; margin-bottom: 36px; }
  header.hero h1 { margin: 0 0 8px; font-size: 30px; letter-spacing: -0.4px; }
  header.hero p { margin: 0; color: var(--ink-2); font-size: 15px; }
  .meta { margin-top: 14px; font-size: 13px; color: var(--ink-2); }
  .meta code { background: var(--accent-soft); padding: 2px 6px; border-radius: 4px; color: var(--accent); }

  nav.toc { margin-bottom: 44px; }
  nav.toc h2 { margin: 0 0 12px; font-size: 14px; color: var(--accent); letter-spacing: 0.5px; }
  nav.toc ul {
    list-style: none; margin: 0; padding: 0;
    display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 10px;
  }
  nav.toc a {
    display: flex; align-items: baseline; gap: 8px;
    padding: 11px 14px; border: 1px solid var(--line); border-radius: 8px;
    background: #fff; color: var(--ink); text-decoration: none; font-size: 14px;
    transition: border-color .15s, background .15s, transform .15s;
  }
  nav.toc a:hover { border-color: var(--accent); background: var(--accent-soft); transform: translateY(-1px); }
  .tag {
    flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px; font-weight: 700; color: var(--accent);
    background: var(--accent-soft); border-radius: 4px; padding: 1px 6px;
  }

  section { margin-bottom: 56px; scroll-margin-top: 20px; }
  section > h2 { font-size: 21px; margin: 0 0 6px; display: flex; align-items: center; gap: 10px; }
  section > .sub { margin: 0 0 20px; color: var(--ink-2); font-size: 14px; }

  figure.chart { margin: 0; border: 1px solid var(--line); border-radius: 10px; padding: 20px 18px 10px; background: #fff; }
  .diagram { display: flex; justify-content: center; overflow-x: auto; }
  .diagram > svg { display: block; max-width: none; cursor: zoom-in; }
  /* [坑2] 锁死图内字体：节点框宽是渲染时按这条字体链算好的，查看者字体不同就会撑破边框 */
  .diagram > svg text, .diagram > svg tspan {
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
  }
  .diagram::-webkit-scrollbar { height: 8px; }
  .diagram::-webkit-scrollbar-thumb { background: #cbd5e0; border-radius: 4px; }
  figure.chart figcaption { text-align: center; color: #9aa5b1; font-size: 12px; padding: 8px 0 2px; }

  #lb {
    position: fixed; inset: 0; z-index: 99; display: none;
    background: rgba(15, 23, 32, 0.82); cursor: zoom-out;
    align-items: center; justify-content: center; padding: 24px;
  }
  #lb.on { display: flex; }
  #lb .lb-body { max-width: 96vw; max-height: 92vh; overflow: auto; background: #fff; border-radius: 8px; }
  #lb svg { display: block; }
  #lb .lb-close {
    position: absolute; top: 16px; right: 22px; width: 38px; height: 38px;
    border: none; border-radius: 50%; background: rgba(255,255,255,.16); color: #fff;
    font-size: 22px; line-height: 1; cursor: pointer;
  }
  #lb .lb-close:hover { background: rgba(255,255,255,.3); }

  table.anchor { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 13.5px; }
  table.anchor caption { text-align: left; color: var(--ink-2); font-size: 13px; padding-bottom: 8px; }
  table.anchor th, table.anchor td { border: 1px solid var(--line); padding: 8px 12px; text-align: left; vertical-align: top; }
  table.anchor code { background: #f5f7fa; padding: 1px 5px; border-radius: 3px; font-size: 12.5px; }

  .note { background: var(--amber-soft); border-left: 4px solid var(--amber); border-radius: 0 8px 8px 0; padding: 14px 18px; margin-top: 20px; font-size: 14px; color: #7c3d0b; }
  .note b { color: #7c2d12; }
  .note ul { margin: 8px 0 0; padding-left: 20px; }
  .info { background: var(--blue-soft); border-left: 4px solid var(--blue); border-radius: 0 8px 8px 0; padding: 14px 18px; margin-top: 20px; font-size: 14px; color: #1e3a8a; }

  footer { margin-top: 72px; padding-top: 20px; border-top: 1px solid var(--line); color: #9aa5b1; font-size: 12.5px; }
</style>
</head>
<body>
<div class="wrap">

<header class="hero">
  <h1>${manifest.title}</h1>
  <p>${manifest.subtitle || ''}</p>
  <div class="meta">
    ${meta}
  </div>
</header>

<nav class="toc">
  <h2>目录</h2>
  <ul>
${toc}
  </ul>
</nav>

${blocks.join('\n\n')}

<footer>
  ${manifest.footer || ''}
</footer>

</div>

<div id="lb" aria-hidden="true">
  <button class="lb-close" type="button" aria-label="关闭">×</button>
  <div class="lb-body"></div>
</div>

<script>
(function () {
  var lb = document.getElementById('lb');
  var body = lb.querySelector('.lb-body');

  function fit(svg) {
    var vb = svg.getAttribute('viewBox');
    if (!vb) return;
    var p = vb.split(/\\s+/);
    var w = parseFloat(p[2]), h = parseFloat(p[3]);
    if (!w || !h) return;
    // 只按宽度放大，纵向交给滚动：按高度收缩会让高图反而放不大
    var scale = (window.innerWidth * 0.92) / w;
    if (scale < 1) scale = 1;
    if (scale > 2.4) scale = 2.4;
    svg.style.width = Math.round(w * scale) + 'px';
    svg.style.height = Math.round(h * scale) + 'px';
  }

  document.querySelectorAll('.diagram > svg').forEach(function (svg) {
    svg.addEventListener('click', function () {
      var clone = svg.cloneNode(true);
      clone.removeAttribute('style');
      clone.removeAttribute('width');
      clone.removeAttribute('height');
      body.innerHTML = '';
      body.appendChild(clone);
      lb.classList.add('on');
      lb.setAttribute('aria-hidden', 'false');
      fit(clone);
    });
  });

  function close() {
    lb.classList.remove('on');
    lb.setAttribute('aria-hidden', 'true');
    body.innerHTML = '';
  }
  lb.addEventListener('click', close);
  lb.querySelector('.lb-close').addEventListener('click', close);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });
  window.addEventListener('resize', function () {
    var s = body.querySelector('svg');
    if (s) fit(s);
  });
})();
</script>
</body>
</html>
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });

// 必须 fsync：覆盖写大文件后，macOS APFS 上紧随其后的 statSync / 读取可能拿到过期的 inode size，
// 导致日志打印错误大小，更糟的是让紧接着跑的验收脚本读到不完整内容。
const fd = fs.openSync(OUT, 'w');
try {
  fs.writeFileSync(fd, html);
  fs.fsyncSync(fd);
} finally {
  fs.closeSync(fd);
}
console.log('已写入', OUT, '/', Buffer.byteLength(html, 'utf8'), 'bytes /', manifest.sections.length, '张图');

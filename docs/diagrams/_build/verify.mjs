/**
 * 验收步骤：用无头浏览器打开产物 HTML，检查样式是否生效、文字是否溢出、放大是否可用。
 *
 * 用法：node verify.mjs <产物HTML路径>
 * 依赖：puppeteer。可 npm i puppeteer，或用 PUPPETEER_MODULE_PATH 指向已有安装。
 *
 * 六项检查：DOM 完整性 / 样式生效 / 文字溢出 / marker 悬空引用 / 放大交互 / JS 报错。
 * 任何一项失败都会以非 0 退出码结束，便于接入 CI。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const arg = process.argv[2];
const target = arg ? path.resolve(arg) : '';
if (!target || !fs.existsSync(target)) {
  // 打印解析后的绝对路径：相对路径层级算错是最常见的用法错误
  console.error('用法: node verify.mjs <产物HTML路径>');
  if (arg) console.error(`文件不存在：${target}`);
  process.exit(2);
}

// 兼容多种安装位置：环境变量 > 当前目录 > 全局托管目录
const candidates = [
  process.env.PUPPETEER_MODULE_PATH,
  path.resolve('node_modules/puppeteer'),
  '/tmp/mmdc/node_modules/puppeteer',
].filter(Boolean);
let puppeteer = null;
for (const c of candidates) {
  try {
    const mod = createRequire(path.join(c, 'package.json'))('puppeteer');
    if (mod?.launch) { puppeteer = mod; break; }
  } catch { /* 继续尝试下一个 */ }
}
if (!puppeteer) {
  console.error('未找到 puppeteer。请 npm i puppeteer，或设置 PUPPETEER_MODULE_PATH。');
  process.exit(2);
}

const problems = [];
const browser = await puppeteer.launch({
  args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 1000 });

const jsErrors = [];
page.on('pageerror', e => jsErrors.push(String(e)));
page.on('console', m => { if (m.type() === 'error') jsErrors.push(m.text()); });

await page.goto(`file://${path.resolve(target)}`, { waitUntil: 'networkidle0' });

const report = await page.evaluate(() => {
  const out = { svgs: 0, sections: 0, styled: 0, unstyled: [], overflow: [], dangling: [] };
  out.svgs = document.querySelectorAll('.diagram > svg').length;
  out.sections = document.querySelectorAll('section[id]').length;

  // 1) 样式生效：取每个节点的首个形状，检查是否带填充色（失效时会退化成 none/透明）
  document.querySelectorAll('.diagram > svg').forEach((svg, si) => {
    const shapes = [...svg.querySelectorAll('g.node rect, g.node path, g.node polygon, g.node circle')];
    if (!shapes.length) return;
    let styledCount = 0;
    for (const sh of shapes.slice(0, 6)) {
      const cs = getComputedStyle(sh);
      const fill = cs.fill || '';
      if (fill && fill !== 'none' && !/rgba\(0,\s*0,\s*0,\s*0\)/.test(fill)) styledCount++;
    }
    if (styledCount > 0) out.styled++;
    else out.unstyled.push(`图${si + 1}`);

    // 2) 文字溢出：标签包围盒超出节点形状包围盒即为溢出
    svg.querySelectorAll('g.node').forEach(node => {
      const shape = node.querySelector('rect, path, polygon, circle, ellipse');
      const label = node.querySelector('g.label, text');
      if (!shape || !label) return;
      const a = shape.getBoundingClientRect();
      const b = label.getBoundingClientRect();
      if (!a.width || !b.width) return;
      const dx = Math.max(0, a.left - b.left) + Math.max(0, b.right - a.right);
      const dy = Math.max(0, a.top - b.top) + Math.max(0, b.bottom - a.bottom);
      const over = Math.max(dx, dy);
      if (over > 2) {
        const txt = (label.textContent || '').slice(0, 28);
        out.overflow.push({ svg: si + 1, text: txt, over: Math.round(over) });
      }
    });
  });

  // 3) marker / 渐变等 url(#id) 引用是否都有对应定义
  const defined = new Set();
  document.querySelectorAll('[id]').forEach(el => defined.add(el.id));
  document.querySelectorAll('.diagram > svg').forEach((svg, si) => {
    const refs = new Set();
    svg.querySelectorAll('*').forEach(el => {
      for (const attr of ['marker-start', 'marker-end', 'fill', 'stroke', 'clip-path', 'mask']) {
        const v = el.getAttribute?.(attr);
        const m = v && v.match(/url\(#([^)]+)\)/);
        if (m) refs.add(m[1]);
      }
    });
    refs.forEach(r => { if (!defined.has(r)) out.dangling.push(`图${si + 1}: #${r}`); });
  });

  return out;
});

// 4) id 唯一性（多图内联时最容易撞车）
const html = fs.readFileSync(target, 'utf8');
const svgIds = [...html.matchAll(/<svg id="([^"]+)"/g)].map(m => m[1]);
const dupIds = svgIds.filter((v, i) => svgIds.indexOf(v) !== i);

// 5) 放大交互 + Esc
await page.click('.diagram > svg');
await new Promise(r => setTimeout(r, 300));
const opened = await page.evaluate(() => document.getElementById('lb')?.classList.contains('on'));
const zoomW = await page.evaluate(() => {
  const s = document.querySelector('#lb svg');
  return s ? parseFloat(s.style.width) || 0 : 0;
});
await page.keyboard.press('Escape');
await new Promise(r => setTimeout(r, 200));
const closed = await page.evaluate(() => !document.getElementById('lb')?.classList.contains('on'));

await browser.close();

console.log(`SVG 图数：${report.svgs} / section 数：${report.sections}`);
console.log(`样式生效：${report.styled}/${report.svgs}${report.unstyled.length ? ` 失效：${report.unstyled.join(',')}` : ''}`);
console.log(`文字溢出：${report.overflow.length} 处`
  + (report.overflow.length ? `\n  ${report.overflow.slice(0, 8).map(o => `图${o.svg}「${o.text}」超出 ${o.over}px`).join('\n  ')}` : ''));
console.log(`悬空引用：${report.dangling.length ? report.dangling.join(', ') : '无'}`);
console.log(`svg id：${svgIds.length} 个${dupIds.length ? ` 重复：${dupIds.join(',')}` : '，唯一'}`);
console.log(`放大：${opened ? '可打开' : '打不开'}（宽 ${zoomW}px） / Esc 关闭：${closed ? '正常' : '失败'}`);
console.log(`JS 报错：${jsErrors.length ? jsErrors.slice(0, 3).join(' | ') : '无'}`);
console.log(`my-svg 残留：${(html.match(/my-svg/g) || []).length}`);

// 编辑器会把 data-page-node-id 注入到产物 HTML 的每一个标签上（实测单个 1MB 的图集能被塞进 191KB 垃圾属性）。
// 产物若被注入，说明有人用编辑器手改过而不是走脚本重建——必须重新生成。
const injected = (html.match(/data-page-node-id/g) || []).length;
console.log(`data-page-node-id 注入：${injected}${injected ? ' ← 产物被编辑器污染，请用 build-doc.mjs 重新生成' : ''}`);

if (report.unstyled.length) problems.push('有图样式未生效');
if (report.overflow.length) problems.push(`文字溢出 ${report.overflow.length} 处`);
if (report.dangling.length) problems.push('存在悬空 url(#id) 引用');
if (dupIds.length) problems.push('svg id 重复');
if (!opened || !closed) problems.push('放大/Esc 交互异常');
if (jsErrors.length) problems.push('JS 报错');
if ((html.match(/my-svg/g) || []).length) problems.push('残留 my-svg（样式选择器未替换）');
if (injected) problems.push(`产物被注入 ${injected} 处 data-page-node-id（需用脚本重建）`);

console.log(problems.length ? `\n验收失败：${problems.join('；')}` : '\n验收通过');
process.exit(problems.length ? 1 : 0);

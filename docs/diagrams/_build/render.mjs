/**
 * 渲染步骤：扫描上级目录的 *.mmd → 调用 mmdc 渲染 SVG 到 .render/，并打印每张图尺寸。
 *
 * 用法：node render.mjs
 * 前置：在 _build/ 下执行 npm i @mermaid-js/mermaid-cli（需要 Chromium，脚本会自动下载）
 *
 * 尺寸红线：高度 > 1700px 或宽度 > 1500px 会打 ⚠️，此时应拆图或精简节点文案，
 *           否则塞进预览面板会被等比压缩成不可读的小字。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..');
const WORK = path.join(HERE, '.render-src');
const OUT = path.join(HERE, '.render');

const mmdc = [
  process.env.MMDC_PATH,
  './node_modules/.bin/mmdc',
  path.join(HERE, 'node_modules/.bin/mmdc'),
].filter(Boolean).find(p => fs.existsSync(p));
if (!mmdc) {
  console.error('未找到 mmdc。请先 npm i @mermaid-js/mermaid-cli，或设置 MMDC_PATH 指向可执行文件。');
  process.exit(1);
}

fs.rmSync(WORK, { recursive: true, force: true });
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(WORK, { recursive: true });
fs.mkdirSync(OUT, { recursive: true });

// 编辑器给 Write 写入的文件注入 data-page-node-id，渲染前必须清除，否则 mermaid 语法被破坏
const names = fs.readdirSync(SRC)
  .filter(f => f.endsWith('.mmd'))
  .map(f => f.replace(/\.mmd$/, ''))
  .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));

if (!names.length) {
  console.error(`未找到任何 .mmd 源文件于 ${SRC}`);
  process.exit(1);
}

const order = [];
names.forEach((name, i) => {
  const raw = fs.readFileSync(path.join(SRC, `${name}.mmd`), 'utf8');
  const clean = raw.replace(/\s+data-page-node-id="[^"]*"/g, '');
  if (raw.length !== clean.length) console.log(`  [${name}] 已清除编辑器注入属性`);
  const idx = String(i + 1).padStart(2, '0');
  fs.writeFileSync(path.join(WORK, `d${idx}.mmd`), clean.trim() + '\n');
  order.push({ file: `d${idx}`, name });
});

fs.writeFileSync(path.join(OUT, 'order.json'), JSON.stringify(order, null, 2));

let bad = 0;
for (const { file, name } of order) {
  try {
    execFileSync(mmdc, [
      '-p', path.join(HERE, 'pup.json'),
      '-c', path.join(HERE, 'mermaid-config.json'),
      '-i', path.join(WORK, `${file}.mmd`),
      '-o', path.join(OUT, `${file}.svg`),
      '-b', 'white',
    ], { stdio: 'pipe' });
  } catch (e) {
    console.log(`[${name}] 渲染失败: ${String(e.stderr || e).slice(0, 300)}`);
    bad++;
    continue;
  }
  const svg = fs.readFileSync(path.join(OUT, `${file}.svg`), 'utf8');
  const vb = (svg.match(/viewBox="([^"]+)"/) || [])[1] || '0 0 0 0';
  const [, , w, h] = vb.split(/\s+/).map(Number);
  const over = h > 1700 || w > 1500;
  if (over) bad++;
  console.log(
    `[${name}] ${Math.round(w)} x ${Math.round(h)} ${over ? ' 超界，需拆图或精简文案' : 'ok'}`
  );
}

console.log(`\n共 ${order.length} 张，${bad ? `${bad} 张需要处理` : '全部在尺寸红线内'}`);

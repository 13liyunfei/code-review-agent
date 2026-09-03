import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

import path from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..');
const WORK = path.join(HERE, '.render-src');
const OUT = path.join(HERE, '.render');

const ORDER = [
  'l0-overview',
  'l1a-webhook-intake',
  'l1b-async-review',
  'l2a-prepare-dispatch',
  'l2b-timeout-aggregate',
  'l3-single-agent',
  'l4a-dedup-arbitration',
  'l4b-suppress-grading',
  'l5-workflow',
  'l6-dataflow',
  'l7-capability',
];

fs.rmSync(WORK, { recursive: true, force: true });
fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(WORK, { recursive: true });
fs.mkdirSync(OUT, { recursive: true });

// 编辑器会给 Write 写入的文件注入 data-page-node-id，渲染前必须清除
ORDER.forEach((name, i) => {
  let src = fs.readFileSync(path.join(SRC, `${name}.mmd`), 'utf8');
  const before = src.length;
  src = src.replace(/\s+data-page-node-id="[^"]*"/g, '');
  const idx = String(i + 1).padStart(2, '0');
  fs.writeFileSync(path.join(WORK, `d${idx}.mmd`), src.trim() + '\n');
  if (before !== src.length) console.log(`  [${name}] 已清除注入属性`);
});

const files = fs.readdirSync(WORK).filter(f => f.endsWith('.mmd')).sort();
for (const f of files) {
  const n = path.basename(f, '.mmd');
  try {
    execFileSync('./node_modules/.bin/mmdc',
      ['-p', 'pup.json', '-c', 'mermaid-config.json', '-i', `${WORK}/${f}`, '-o', `${OUT}/${n}.svg`, '-b', 'white'],
      { stdio: 'pipe' });
  } catch (e) {
    console.log(`[${n}] ❌ 渲染失败: ${String(e.stderr || e).slice(0, 300)}`);
    continue;
  }
  const svg = fs.readFileSync(`${OUT}/${n}.svg`, 'utf8');
  const vb = (svg.match(/viewBox="([^"]+)"/) || [])[1] || '?';
  const [, , w, h] = vb.split(/\s+/).map(Number);
  const name = ORDER[Number(n.slice(1)) - 1];
  const flag = h > 1700 || w > 1500 ? ' ⚠️ 超界' : ' ✅';
  console.log(`[${name}] ${Math.round(w)} x ${Math.round(h)}${flag}`);
}

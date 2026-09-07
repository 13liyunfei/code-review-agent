/**
 * 导出 Markdown 版图集：同一份 manifest.json + *.mmd 源码，产纯文本可维护的文档。
 *
 * 用法：node export-md.mjs
 * 输入：../manifest.json（章节元数据） + ../*.mmd（mermaid 源码）
 * 输出：manifest.outputMd，默认把 manifest.output 的扩展名换成 .md
 *
 * 与 build-doc.mjs 的区别：那份产内联 SVG 的离线 HTML（看图用），这份产 mermaid
 * 源码的可版本化文档（走查 / 贴 GitHub / 让 LLM 读用）。两者共用一份 manifest，
 * 改文案只改 manifest.json，两份产物一起重跑即可。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, '..');

const manifest = JSON.parse(fs.readFileSync(path.join(SRC, 'manifest.json'), 'utf8'));
const OUT = path.resolve(SRC, manifest.outputMd || (manifest.output || '../diagrams.html').replace(/\.html?$/, '.md'));

/** manifest 里的文案是给 HTML 写的，这里降级成 Markdown（够用即可，不追求完备）。 */
function html2md(h) {
  let s = String(h || '');
  s = s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&');
  s = s.replace(/<br\s*\/?>/g, '\n');
  s = s.replace(/<\/?(ul|ol)>/g, '\n');
  s = s.replace(/<li>/g, '\n- ').replace(/<\/li>/g, '');
  s = s.replace(/<b>([\s\S]*?)<\/b>/g, '**$1**');
  s = s.replace(/<strong>([\s\S]*?)<\/strong>/g, '**$1**');
  s = s.replace(/<i>([\s\S]*?)<\/i>/g, '*$1*');
  s = s.replace(/<code>([\s\S]*?)<\/code>/g, '`$1`');
  s = s.replace(/<[^>]+>/g, '');
  s = s.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n');
  return s.trim();
}

/**
 * 把一段文案包成引用块。列表前后必须补空引用行（只写 `>`），否则 GFM 会把
 * 列表后紧跟的段落当成最后一项的 lazy continuation，整段缩进进列表里。
 */
function quoteBlock(label, text) {
  const lines = html2md(text).split('\n');
  const out = [`> **${label}**`];
  lines.forEach((cur, i) => {
    const prev = lines[i - 1] || '';
    const isItem = /^- /.test(cur);
    const prevItem = /^- /.test(prev);
    if ((isItem && prev && !prevItem) || (!isItem && cur && prevItem)) out.push('>');
    out.push(cur ? `> ${cur}` : '>');
  });
  return out.join('\n');
}

/** 编辑器给 Write 写入的文件注入 data-page-node-id，导出前必须清掉（同 render.mjs）。 */
function readMmd(name) {
  const raw = fs.readFileSync(path.join(SRC, `${name}.mmd`), 'utf8');
  const clean = raw.replace(/\s+data-page-node-id="[^"]*"/g, '');
  if (raw.length !== clean.length) console.log(`  [${name}] 已清除编辑器注入属性`);
  return clean.trim();
}

const toc = manifest.sections
  .map(s => `- [${s.tag || s.id} · ${s.title}](#${s.id})`)
  .join('\n');

const body = manifest.sections.map(s => {
  const mmd = readMmd(s.file);
  const parts = [];
  parts.push(`<a id="${s.id}"></a>\n`);
  parts.push(`## ${s.tag || s.id} · ${s.title}\n`);
  if (s.sub) parts.push(`${html2md(s.sub)}\n`);
  parts.push('```mermaid\n' + mmd + '\n```\n');
  parts.push(`> 源码：\`docs/diagrams/${s.file}.mmd\``);
  if (s.anchors && s.anchors.length) {
    parts.push('\n**源码锚点**\n');
    parts.push('| 说明 | 位置 |\n| --- | --- |');
    parts.push(s.anchors.map(([k, v]) => `| ${html2md(k)} | ${html2md(v)} |`).join('\n') + '\n');
  } else {
    parts.push('');
  }
  if (s.info) parts.push(`${quoteBlock('要点', s.info)}\n`);
  if (s.note) parts.push(`${quoteBlock('注意', s.note)}\n`);
  return parts.join('\n');
}).join('\n---\n\n');

const meta = (manifest.meta || []).map(html2md).map(m => `- ${m.replace(/\n/g, ' ')}`).join('\n');

const md = `# ${manifest.title}

${manifest.subtitle || ''}

${meta}

> 本文由 \`docs/diagrams/_build/export-md.mjs\` 从 \`manifest.json\` + \`*.mmd\` 生成，**不要手改**。
> 改图改 \`*.mmd\`，改文案改 \`manifest.json\`，然后 \`node export-md.mjs\` 重跑。
> 带内联 SVG 的可视化版本见 [\`business-flow.html\`](${path.basename(manifest.output || 'business-flow.html')})。

## 目录

${toc}

---

${body}

---

${html2md(manifest.footer || '').split('\n').map(l => l.trim()).join('\n')}
`;

const fd = fs.openSync(OUT, 'w');
try {
  fs.writeFileSync(fd, md);
  fs.fsyncSync(fd);
} finally {
  fs.closeSync(fd);
}
console.log('已写入', OUT, '/', Buffer.byteLength(md, 'utf8'), 'bytes /', manifest.sections.length, '张图');

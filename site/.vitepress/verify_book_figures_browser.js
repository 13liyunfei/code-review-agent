// 专栏插图「浏览器级」验收：在真实 Chromium 里判图有没有真的画出来。
//
// 为什么还需要这一层：
//   verify_book_figures.py --dist 是扫 HTML 字符串，能抓住「标签被转义成文本」，
//   但抓不到「标签在、资源也 200，可是 CSS 没生效 / 被裁成 0 高 / 图根本不显示」。
//   本脚本用浏览器量：加载成功（naturalWidth>0）+ 可见尺寸 + 图数对得上。
//
// 依赖：puppeteer（本机装在 /Users/yunfei/node_modules，非仓库依赖）。
// 用法：
//   # 1) 起本地预览（base 是 /code-review-agent/，所以要让 dist 挂在同名子目录下）
//   mkdir -p /tmp/site_preview && ln -sfn "$(pwd)/site/.vitepress/dist" /tmp/site_preview/code-review-agent
//   (cd /tmp/site_preview && python3 -m http.server 8877 --bind 127.0.0.1 &)
//   # 2) 跑
//   NODE_PATH=/Users/yunfei/node_modules node site/.vitepress/verify_book_figures_browser.js \
//       http://127.0.0.1:8877 site/.vitepress/dist
//
// 退出码非 0 即失败。期望张数取自 site/zh/book 下的 md 源文件（不是从产物自己数）。
const puppeteer = require('/Users/yunfei/node_modules/puppeteer');
const fs = require('fs');
const path = require('path');

const [base, distDir] = process.argv.slice(2);
if (!base || !distDir) {
  console.error('用法: node verify_book_figures_browser.js <baseUrl> <distDir>');
  process.exit(2);
}
const mdDir = path.resolve(__dirname, '..', 'zh', 'book');

function findChrome() {
  const base2 = path.join(process.env.HOME, '.cache/puppeteer/chrome');
  for (const v of fs.readdirSync(base2).sort().reverse()) {
    const p = path.join(base2, v, 'chrome-mac-arm64',
      'Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing');
    if (fs.existsSync(p)) return p;
  }
  throw new Error('未找到 chrome-for-testing：' + base2);
}

// 源文件侧：每讲预期几张 mermaid 图
function expected() {
  const map = {};
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.md')) {
        const s = fs.readFileSync(p, 'utf8');
        const n = (s.match(/<img class="mermaid-svg"/g) || []).length;
        if (n) map[path.relative(mdDir, p).replace(/\.md$/, '')] = n;
        else if (e.name === 'index.md') map['index'] = 0;
      }
    }
  };
  walk(mdDir);
  return map;
}

(async () => {
  const exp = expected();
  const distBook = path.join(distDir, 'zh', 'book');
  const rels = [];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.html')) rels.push(path.relative(distBook, p).replace(/\.html$/, ''));
    }
  };
  walk(distBook);

  const browser = await puppeteer.launch({
    executablePath: findChrome(), headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--no-proxy-server'],
  });

  let bad = 0, imgs = 0, broken = 0, missingInMap = 0;
  for (const rel of rels.sort()) {
    const want = exp[rel];
    if (want === undefined) { missingInMap++; }
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 1200 });
    const errs = [], failed = [];
    page.on('pageerror', e => errs.push(String(e).slice(0, 120)));
    // favicon 是浏览器隐式探测，不是页面引用的资源 → 不计入
    page.on('response', r => {
      if (r.status() >= 400 && !/favicon\.ico$/.test(r.url())) failed.push(r.status() + ' ' + r.url().slice(-60));
    });
    await page.goto(base + '/code-review-agent/zh/book/' + rel + '.html',
      { waitUntil: 'networkidle0', timeout: 60000 });
    await new Promise(r => setTimeout(r, 400));

    const rep = await page.evaluate(() => {
      const im = [...document.querySelectorAll('img.mermaid-svg')];
      return {
        srcText: document.body.innerText.includes('<img class="mermaid-svg"'),
        n: im.length,
        loaded: im.filter(i => i.naturalWidth > 0).length,
        sized: im.filter(i => i.getBoundingClientRect().width > 40 && i.getBoundingClientRect().height > 20).length,
      };
    });

    const problems = [];
    if (rep.srcText) problems.push('正文里出现 <img class="mermaid-svg"> 源码（图没渲染）');
    if (rep.loaded !== rep.n) problems.push(`加载失败 ${rep.n - rep.loaded} 张`);
    if (rep.sized !== rep.n) problems.push(`可见尺寸异常 ${rep.n - rep.sized} 张`);
    if (want !== undefined && rep.n !== want) problems.push(`图数 ${rep.n} ≠ 源文件 ${want}`);
    if (errs.length) problems.push('JS 错误: ' + errs.join(' | '));
    if (failed.length) problems.push('资源请求失败: ' + failed.slice(0, 3).join(' | '));

    imgs += rep.n;
    broken += (rep.n - rep.loaded) + (rep.n - rep.sized);
    const ok = problems.length === 0;
    if (!ok) bad++;
    console.log(`${ok ? 'OK  ' : 'BAD '} ${rel}  图 ${rep.n}${want !== undefined ? '/' + want : ''} 加载 ${rep.loaded} 可见 ${rep.sized}`);
    problems.forEach(p => console.log('      !! ' + p));
    await page.close();
  }
  await browser.close();
  if (missingInMap) console.log(`（另有 ${missingInMap} 个产物页在源文件侧找不到对应 md）`);
  console.log(`\n合计：扫 ${rels.length} 页，${imgs} 张图，异常 ${broken} 张，BAD 页 ${bad}`);
  process.exit(bad ? 1 : 0);
})();

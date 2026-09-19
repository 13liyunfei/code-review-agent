// 线上专栏「浏览器级」验收：真实 Chromium 打开 GitHub Pages 上的每一讲。
const puppeteer = require('/Users/yunfei/node_modules/puppeteer');
const fs = require('fs'), path = require('path');

const BASE = 'https://13liyunfei.github.io/code-review-agent';
const PROXY = process.env.HTTPS_PROXY || '';
const mdDir = path.join(process.env.HOME, 'IdeaProjects/code-review-agent/site/zh/book');
const shotDir = '/tmp/online_shots';
fs.mkdirSync(shotDir, { recursive: true });

function findChrome() {
  const b = path.join(process.env.HOME, '.cache/puppeteer/chrome');
  for (const v of fs.readdirSync(b).sort().reverse()) {
    const p = path.join(b, v, 'chrome-mac-arm64', 'Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing');
    if (fs.existsSync(p)) return p;
  }
  throw new Error('no chrome');
}

function expected() {
  const map = {};
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.md')) {
        const s = fs.readFileSync(p, 'utf8');
        const n = (s.match(/<img class="(?:mermaid|arch)-svg"/g) || []).length;
        map[path.relative(mdDir, p).replace(/\.md$/, '')] = n;
      }
    }
  };
  walk(mdDir);
  return map;
}

(async () => {
  const exp = expected();
  const rels = Object.keys(exp).sort();
  const args = ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'];
  if (PROXY) args.push('--proxy-server=' + PROXY);
  const browser = await puppeteer.launch({ executablePath: findChrome(), headless: true, args });

  let bad = 0, total = 0;
  const widths = [];
  const queue = rels.slice();
  const worker = async () => {
    while (queue.length) {
      const rel = queue.shift();
      const want = exp[rel];
      const page = await browser.newPage();
      await page.setViewport({ width: 1440, height: 1200 });
      const errs = [], failed = [];
      page.on('pageerror', e => errs.push(String(e).slice(0, 120)));
      page.on('response', r => {
        if (r.status() >= 400 && !/favicon\.ico$/.test(r.url())) failed.push(r.status() + ' ' + r.url().slice(-60));
      });
      let rep;
      try {
        await page.goto(BASE + '/zh/book/' + rel + '.html', { waitUntil: 'networkidle0', timeout: 90000 });
        await new Promise(r => setTimeout(r, 500));
        rep = await page.evaluate(() => {
          const im = [...document.querySelectorAll('img.mermaid-svg, img.arch-svg')];
          const box = im.map(i => {
            const b = i.getBoundingClientRect();
            return [Math.round(b.width), Math.round(b.height), i.naturalWidth, i.naturalHeight, i.className];
          });
          return {
            srcText: document.body.innerText.includes('<img class="mermaid-svg"')
                  || document.body.innerText.includes('<img class="arch-svg"'),
            n: im.length,
            loaded: im.filter(i => i.naturalWidth > 0).length,
            sized: im.filter(i => i.getBoundingClientRect().width > 40 && i.getBoundingClientRect().height > 20).length,
            box,
          };
        });
      } catch (e) {
        console.log('BAD ' + rel + '  打开失败: ' + String(e).slice(0, 100));
        bad++; await page.close(); continue;
      }
      const problems = [];
      if (rep.srcText) problems.push('正文出现 <img> 源码');
      if (rep.loaded !== rep.n) problems.push('加载失败 ' + (rep.n - rep.loaded));
      if (rep.sized !== rep.n) problems.push('尺寸异常 ' + (rep.n - rep.sized));
      if (rep.n !== want) problems.push('图数 ' + rep.n + ' != 源 ' + want);
      if (errs.length) problems.push('JS 错误 ' + errs.join('|'));
      if (failed.length) problems.push('资源失败 ' + failed.slice(0, 2).join('|'));
      total += rep.n;
      rep.box.forEach(b => widths.push(b));
      if (problems.length) {
        bad++;
        console.log('BAD ' + rel + '  图 ' + rep.n + '/' + want + '\n     !! ' + problems.join('; '));
      }
      for (const b of rep.box) if (b[2] === 0) console.log('   !! 零宽资源 ' + rel + ' ' + JSON.stringify(b));
      await page.close();
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  await browser.close();
  const ws = widths.map(w => w[0]).sort((a, b) => a - b);
  const nat = widths.map(w => w[2]).sort((a, b) => a - b);
  const ratio = widths.map(w => (w[0] ? w[0] / w[2] : 0)).sort((a, b) => a - b);
  console.log('\n合计：扫 ' + rels.length + ' 页 / ' + total + ' 张图 / BAD 页 ' + bad);
  if (ws.length) {
    console.log('渲染宽度  min=' + ws[0] + ' 中位=' + ws[ws.length >> 1] + ' max=' + ws[ws.length - 1]);
    console.log('固有宽度  min=' + nat[0] + ' 中位=' + nat[nat.length >> 1] + ' max=' + nat[nat.length - 1]);
    console.log('缩放比    中位=' + ratio[ratio.length >> 1].toFixed(3) + ' 最小=' + ratio[0].toFixed(3));
    const squeezed = widths.filter(w => w[2] > 0 && w[0] / w[2] < 0.5).length;
    console.log('被压到不足一半宽的图 = ' + squeezed + ' 张');
  }
  process.exit(bad ? 1 : 0);
})();

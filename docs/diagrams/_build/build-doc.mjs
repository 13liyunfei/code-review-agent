import fs from 'node:fs';

import path from 'node:path';
import { fileURLToPath } from 'node:url';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(HERE, '.render');
const OUT = path.join(HERE, '..', '..', 'business-flow.html');

// 顺序需与 render.mjs 的 ORDER 一致
const SECTIONS = [
  {
    id: 'l0', tag: 'L0', file: 'd01', w: 0,
    title: '系统全景',
    sub: '五个入口 → 引擎内部六段流水线 → 平台四类回写动作。多租户以 <code>teamId</code> 贯穿全链路。',
    info: '<b>拓扑是「星型汇聚」而非流水线</b>：5 个内置 Agent 之间互不通信、无依赖，协作只发生在聚合阶段（去重 / 仲裁 / 抑制）。这是与「Agent 链式传递结果」架构的根本差别。',
  },
  {
    id: 'l1a', tag: 'L1a', file: 'd02',
    title: '接入与同步校验',
    sub: 'Webhook 的同步部分<b>只做校验与分发</b>，重活全部丢给 <code>webhookExecutor</code> 异步执行。这样平台侧不会超时重试。',
    anchors: [
      ['验签 / 事件过滤 / 异步分发', '<code>integration/gitea/GiteaWebhookController.java:75-147</code>'],
      ['团队解析', '<code>tenant/TeamResolver.java</code>'],
    ],
    note: '<b>Gitea Webhook 配置的三个坑</b><ul>'
      + '<li><code>events</code> 必须是 <b>布尔字面量</b>，写成字符串不生效；</li>'
      + '<li>重建后的 hook 默认 <code>active=false</code>，需单独 PATCH 打开；</li>'
      + '<li>PATCH <b>不持久化 secret</b>，改密钥必须 DELETE + POST 重建。</li></ul>',
  },
  {
    id: 'l1b', tag: 'L1b', file: 'd03',
    title: '异步审查与回写',
    sub: '<code>GiteaReviewService.reviewPullRequest()</code> 的编排主线：拉 diff → 并行审查 → 生成修复 → 分级路由 → 双通道回写。',
    anchors: [
      ['主链路编排', '<code>integration/gitea/GiteaReviewService.java:99-192</code>'],
      ['报告 Markdown 拼装', '<code>integration/gitea/GiteaReviewService.java:199-219</code>'],
    ],
    note: '<b>Gitea 1.27 的平台限制（踩过的坑）</b><ul>'
      + '<li>逐条直发行内评论的接口返回 <b>405</b>，唯一入口是 <code>POST /pulls/{index}/reviews</code> + <code>comments[]</code> + <code>event:COMMENT</code> 一次性提交；</li>'
      + '<li>PENDING 预建方式下 <code>line</code>/<code>side</code> 会被丢弃，恒降级为文件级评论 → 可采纳修复必须写进「顶层概览 + 行内 suggestion」双通道。</li></ul>',
  },
  {
    id: 'l2a', tag: 'L2a', file: 'd04',
    title: '协调器 · 准备与并行调度',
    sub: '断点续跑 → 上下文增强 → 自定义 Agent 展开 → 规划/固定两种并行路径。',
    anchors: [
      ['断点续跑检测', '<code>coordinator/impl/CompletableFutureCoordinator.java:245-266</code>'],
      ['影响面 + RAG 上下文注入', '<code>:268-295</code>'],
      ['自定义 Agent 展开', '<code>:297-325</code>'],
      ['并行 Future 创建', '<code>:342-359</code>'],
    ],
  },
  {
    id: 'l2b', tag: 'L2b', file: 'd05',
    title: '协调器 · 限时收口与聚合',
    sub: '共享 deadline 逐个收口，超时即中断；随后聚合、否决回收、复检对比与落库。',
    anchors: [
      ['独立限时等待 + 降级', '<code>:373-432</code>'],
      ['聚合后处理（Veto / Profile / 复检 / 落库）', '<code>:447-496</code>'],
      ['断点快照写入', '<code>:503-526</code>'],
    ],
    note: '<b>这里修过一个 P0 缺陷</b>：修复前用 <code>allOf(...).orTimeout()</code>，超时只作用在聚合 future 上，后续逐个 <code>join()</code> 仍然会无限阻塞，且 <code>advancedFuture</code> 完全没进超时体系。现在改为「共享 deadline + 逐个 <code>get(remaining)</code>」，超时即 <code>cancel(true)</code> 中断底层线程。',
  },
  {
    id: 'l3', tag: 'L3', file: 'd06',
    title: '单 Agent 三段式与三级降级',
    sub: '每个 Agent 内部固定三段：<b>注入检测短路</b> → <b>Skill 确定性预扫描</b> → <b>LLM 语义增强</b>。规则出确定结论，LLM 只做补充。',
    anchors: [
      ['三段式范例（SecurityAgent 最完整）', '<code>core/agent/impl/SecurityAgent.java:56-77</code>'],
      ['Skill 并行预扫描', '<code>core/agent/AbstractReviewAgent.java:85-89</code>'],
      ['置信度校准', '<code>core/agent/AbstractReviewAgent.java:97-120</code>'],
      ['LLM 三级降级', '<code>core/agent/AbstractReviewAgent.java:190-244</code>'],
      ['注入检测器实现', '<code>core/security/{Keyword,Semantic}InjectionDetector.java</code>'],
    ],
    info: '<b>降级链路的关键设计</b>：结构化输出失败但 <code>rawResponse</code> 非空时，<b>复用原始输出走文本解析</b>，不再多调一次模型——既省 token，也避免二次失败。',
  },
  {
    id: 'l4a', tag: 'L4a', file: 'd07',
    title: '聚合 · 降级收集 / 去重 / 仲裁',
    sub: '仲裁权重：<b>安全 100 &gt; 逻辑 90 &gt; 性能 70 &gt; 架构 60 &gt; 风格 10</b>；先比 Agent 权重，再比严重度，最后比置信度。',
    anchors: [
      ['降级收集 / 去重 / 仲裁', '<code>core/report/ReportGenerator.java</code>（<code>aggregate</code> 8 参数重载）'],
      ['权重表与冲突判定', '<code>core/report/ArbitrationPolicy.java:21-27, 52-95</code>'],
    ],
  },
  {
    id: 'l4b', tag: 'L4b', file: 'd08',
    title: '聚合 · 误报抑制与强度定级',
    sub: '抑制来自开发者历史反馈；<b>BLOCKER 强否决不可被抑制或仲裁覆盖</b>，被误杀时由 VetoPolicy 回收。',
    anchors: [
      ['BLOCKER 强否决回收', '<code>core/permission/VetoPolicy.java:26-36</code>'],
      ['强度 Profile 过滤', '<code>core/profile/ReviewProfile.java:24-57</code>'],
    ],
  },
  {
    id: 'l5', tag: 'L5', file: 'd09',
    title: '人机协作 · 分级路由决策树',
    sub: '自动审查之后「人」如何介入。<code>ReviewWorkflowEngine.handle()</code> 按 BLOCKER 有无分叉。',
    anchors: [
      ['决策与回写动作', '<code>core/workflow/ReviewWorkflowEngine.java:42-85</code>'],
      ['Issue / Commit Status API', '<code>integration/gitea/GiteaApiClient.java</code>'],
    ],
    info: '工单引用用裸 <code>#N</code> 而非绝对 URL：Gitea 的 auto-link 会渲染为站内可点链接，不会被域名白名单过滤，也不会因 base 二次拼接导致 404。',
  },
  {
    id: 'l6', tag: 'L6', file: 'd10',
    title: '数据流 · 租户存储与状态落盘',
    sub: '所有持久化状态按 <code>teamId</code> 隔离在 <code>data-dir/&lt;teamId&gt;/</code> 下；基线 <code>__global__</code> + 团队叠加，缺失回退默认。',
  },
  {
    id: 'l7', tag: 'L7', file: 'd11',
    title: '能力矩阵 · Agent 与 Skill 树',
    sub: '执行层的完整构成：5 内置 Agent + 团队自定义 Agent + 高级静态分析；Skill 按 category 挂载，支持运行期启停与团队自定义规则。',
    anchors: [
      ['5 个内置 Agent', '<code>core/agent/impl/{Security,Logic,Performance,Architecture,Style}Agent.java</code>'],
      ['自定义 Agent', '<code>core/agent/DeclarativeReviewAgent.java</code>；管理接口 <code>core/admin/AgentAdminController.java</code>'],
      ['高级静态分析', '<code>core/analysis/{AdvancedAnalyzer,AstAnalyzer,CallGraphAnalyzer,ScaScanner}.java</code>'],
      ['Skill 注册与运行期启停', '<code>core/skill/SkillRegistry.java:201-267</code>'],
    ],
  },
];

function prepareSvg(path, idx) {
  let svg = fs.readFileSync(path, 'utf8');
  svg = svg.replace(/<\?xml[^>]*\?>\s*/g, '').replace(/<!DOCTYPE[^>]*>\s*/g, '');
  const vb = (svg.match(/viewBox="([^"]+)"/) || [])[1] || '0 0 800 600';
  const [, , vw, vh] = vb.split(/\s+/).map(Number);
  const id = `flow-${idx}`;
  // 关键：mermaid 把根 id、样式选择器前缀、箭头 marker id 全部写死成 my-svg。
  // 只改根 id 不改 style 里的 #my-svg，整套配色/描边/连线样式会失效，只剩默认黑线框；
  // 不改 marker id 则 11 张图的箭头标记互相撞车。这里统一替换。
  svg = svg.replace(/my-svg/g, id);
  svg = svg.replace(/<svg([^>]*)>/, (m, attrs) => {
    const a = attrs
      .replace(/\sid="my-svg"/, ` id="${id}"`)
      .replace(/\swidth="[^"]*"/, ` width="${Math.ceil(vw)}"`)
      .replace(/\sstyle="[^"]*"/, ' style="max-width:none;background-color:transparent;"');
    return `<svg${a} height="${Math.ceil(vh)}">`;
  });
  return { svg, w: Math.ceil(vw), h: Math.ceil(vh) };
}

const blocks = SECTIONS.map((s, i) => {
  const { svg, w, h } = prepareSvg(`${OUT_DIR}/${s.file}.svg`, i + 1);
  const anchors = s.anchors && s.anchors.length
    ? `\n  <table class="anchor">\n    <caption>源码锚点</caption>\n`
      + s.anchors.map(([k, v]) => `    <tr><td>${k}</td><td>${v}</td></tr>`).join('\n')
      + `\n  </table>`
    : '';
  return `<section id="${s.id}">
  <h2><span class="tag">${s.tag}</span>${s.title}</h2>
  <p class="sub">${s.sub}</p>
  <figure class="chart">
    <div class="diagram">${svg}</div>
    <figcaption>点击放大 · 原图 ${w} × ${h}</figcaption>
  </figure>${anchors}
  ${s.info ? `\n  <div class="info">${s.info}</div>` : ''}
  ${s.note ? `\n  <div class="note">${s.note}</div>` : ''}
</section>`;
});

const toc = SECTIONS.map(s =>
  `    <li><a href="#${s.id}"><span class="tag">${s.tag}</span>${s.title}</a></li>`).join('\n');

const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>code-review-agent · 完整业务流程图集</title>
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
  /* 锁死图内字体：节点框宽是渲染时按此字体链算好的，换字体会撑破边框 */
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
  <h1>code-review-agent · 完整业务流程图集</h1>
  <p>从「PR 被推送」到「评论回写平台」的全链路，逐层拆解到单 Agent 内部与聚合仲裁细节。</p>
  <div class="meta">
    入口：<code>GiteaWebhookController</code> · <code>GitLabWebhookController</code> · <code>ScheduledScanService</code> · <code>ReviewApiController</code> · <code>IdeReviewServer</code><br>
    编排：<code>GiteaReviewService</code> → <code>CompletableFutureCoordinator</code> → <code>ReportGenerator</code>
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
  code-review-agent · 业务流程图集 · 全部节点均可在 <code>src/main/java/com/codereview/</code> 下按文件路径 grep 复现<br>
  mermaid 源码：<code>docs/diagrams/*.mmd</code>
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
    // 放大到填满宽度即可，纵向由容器滚动查看；不按高度收缩，否则高图反而放不大
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

fs.writeFileSync(OUT, html);
console.log('已写入', OUT, fs.statSync(OUT).size, 'bytes /', SECTIONS.length, '张图');

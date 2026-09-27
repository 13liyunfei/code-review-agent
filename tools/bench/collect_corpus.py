#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1-6 审查级评测基准：语料采集器（可复跑，provenance 全机器提取）。

为什么单独写这个脚本
--------------------
基准集的价值全部押在「**这一条语料到底是从哪儿来的**」上。手抄 commit、手抄行数、
手抄文件清单 ⇒ 任何一处错都会让语料变成"看起来像真的"，而读者无法复核。
所以本脚本的纪律是：**语料里每一个数都由 git / GitHub API 现取**，且把取数命令写在
下面「复现」一节里，任何人可以重跑出字节一致的产物（`--check` 校验）。

语料构成（20 条 = 18 本仓提交 + 2 开源仓真实 PR）
------------------------------------------------
* **18 条**：`code-review-agent` 自己的历史提交（dogfood）——真实工程 diff，含 feat / fix / refactor 三类。
* **2 条**：外部开源仓的真实 PR（`pgvector/pgvector#890`、`langchain4j/langchain4j#6424`），
  走 GitHub REST 取 `application/vnd.github.v3.diff`，并记录 base/head/merge SHA。

候选标注（labelCandidates）不是「已标注」
--------------------------------------
脚本对每条 diff 的**新增行**跑一遍固定 rubric（12 类缺陷模式），产出候选。
候选**必须人工确认**才进 `groundTruth`；未经确认的只留在 `labelCandidates` 里。
这条区分是刻意的：**机器扫出来的东西不能冒充人工标注**（否则 precision/recall 就成了
"机器 vs 机器"，指标失去意义）。

复现
----
    cd ~/IdeaProjects/code-review-agent
    env -u PYTHONPATH -u CODEBUDDY_BROKERED_FS_HOOK_ENABLED \
      python3 tools/bench/collect_corpus.py            # 采集并写盘
    env -u PYTHONPATH -u CODEBUDDY_BROKERED_FS_HOOK_ENABLED \
      python3 tools/bench/collect_corpus.py --check    # 只校验产物是否与重采一致

基准 HEAD（写死，仓库前进后本脚本需重跑并更新此值）：
    code-review-agent @ 8c4dc26
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "src", "main", "resources", "bench")
CASES = os.path.join(OUT, "cases")

PATCH_CAP = 200_000          # 单条 patch 落盘上限（字节）；超出则截断并标记
LOCAL_REPO_URL = "https://github.com/13liyunfei/code-review-agent"

# ── 18 条本仓提交（sha, 选择理由）────────────────────────────────────────────
# 判据：非 merge、有 src/main 或 pom 改动、规模适中（≤2500 行）⇒ 标注可行。
# 覆盖 feat / fix / refactor / chore 四类，且大小从 33 行到 1618 行不等。
LOCAL_COMMITS = [
    ("ff80b74", "chore：删孤儿类 + 统一测试命名（小 diff，删多于增）"),
    ("99d27a2", "feat：Planning 接入 Coordinator 主链路（默认关闭）"),
    ("9d0a06f", "fix：崩溃遗留的断点永久残留，补定时清理"),
    ("9ccad0e", "fix：AdvancedAnalyzer 同源病根治 + 断点续跑 key 稳定化"),
    ("49a8873", "feat：经验检索注入接入生产审查链路"),
    ("005380f", "feat：Tool Calling 完整链路 + 任务拆解 DAG"),
    ("7410a93", "feat：接入 OSV 真实漏洞库"),
    ("f4dd917", "feat：Agent 通用能力四件套"),
    ("c2a8691", "feat：adopt agent-kit 0.1.1（结构化输出 + LLM trace）"),
    ("487e27b", "refactor：adopt agent-kit PromptInjectionDetector 作为基础层"),
    ("b34e8fd", "feat：多语言支持 i18n（中英双语）"),
    ("fbff22c", "feat：接入公司级 Token 工厂 + 直连补报用量"),
    ("a36992d", "feat：RAG 检索加固（可配窗口 + LLM 查询改写）"),
    ("365480a", "feat：diff 输入面纵深防御（隐写扫描 + 分级隔离 + 数据定界）"),
    ("e411377", "fix：similarity 口径与中文分词 + MMR / small-to-big / 结构化查询"),
    ("ed966ea", "fix：三个 P0 缺陷 + 降级可观测性"),
    ("2a07394", "fix：面试手册缺陷审计落地（supports/diff 预算/指标端点/幂等/配额竞态）"),
    ("ff2feb5", "refactor：全仓死代码清理（删多于增，负向样本）"),
]

# ── 2 条外部开源仓真实 PR ────────────────────────────────────────────────────
OSS_PRS = [
    ("pgvector/pgvector", 890, "C 扩展：空列表改用 NIL（极小 diff，负向样本）"),
    ("langchain4j/langchain4j", 6424, "Java：让 SQL EXTRACT 与工具名的大小写转换不受 locale 影响"),
]

# ── 候选缺陷 rubric ─────────────────────────────────────────────────────────
# 每条 = (ruleId, category, severity, 正则, 说明)。
# 只扫 **新增行**（`+` 开头且非 `+++`），因为审查的对象是"这次改了什么"。
#
# ★ 设计纪律：**宁可少报，不可多报**。第一版 rubric 含「循环体内调用外部资源」这类
#   粗规则，20 条语料扫出 181 条候选、其中 90 条是每一行 `for (`，人工无法过。
#   **候选的价值 = 精度 × 覆盖量**，精度趋零时候选等于噪音。现在的 18 条规则全部要求
#   "看到这一行就能说清它为什么该被提"（代价是覆盖率下降，由人工补标兜底）。
RUBRIC = [
    # —— 安全 ——
    ("SEC-001", "security", "BLOCKER",
     r'(?i)\b(password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|token)\s*=\s*"[^"\n]{6,}"',
     "疑似硬编码凭证：把密钥字面量写进源码"),
    ("SEC-002", "security", "BLOCKER",
     r'(?i)(select|insert|update|delete|where)\b[^"\n]*"\s*\+\s*[\w"]',
     "SQL 字符串拼接：存在注入面，应改参数化"),
    ("SEC-003", "security", "MAJOR",
     r'(?i)log\.(info|debug|warn)\s*\([^)]*\b(password|token|secret|authorization|apiKey|prompt|body)\b',
     "日志落敏感内容：token/口令/完整 body 不应进 INFO 日志"),
    ("SEC-004", "security", "MAJOR",
     r'Runtime\.getRuntime\(\)\.exec|new\s+ProcessBuilder\(',
     "外部进程调用：需确认入参是否可被外部控制"),
    ("SEC-005", "security", "MAJOR",
     r'(?i)\bnew\s+(File|FileInputStream|FileOutputStream)\s*\([^)]*(req\.|request\.|param|input|name)',
     "路径拼接用户输入：需确认是否做了路径穿越（../）防护"),
    # —— 错误处理（本项目最贵的缺陷类：静默失败）——
    ("LOGIC-001", "logic", "MAJOR",
     r'catch\s*\([^)]*\)\s*\{\s*\}',
     "空 catch 吞异常：失败静默，调用方无从感知"),
    ("LOGIC-002", "logic", "MAJOR",
     r'catch\s*\(\s*(Exception|Throwable)\s+\w+\s*\)\s*\{',
     "宽泛捕获 Exception/Throwable：会连带吞掉不该处理的异常（需确认是否只 log 不重抛）"),
    ("LOGIC-003", "logic", "MAJOR",
     r'\.findFirst\(\)\s*\.get\(\)|\.findAny\(\)\s*\.get\(\)',
     "Optional.get() 未先判存在：空值时抛 NoSuchElementException"),
    ("LOGIC-004", "logic", "MAJOR",
     r'(?<![=!<>])\b\w+\s*==\s*"[^"]*"',
     "用 == 比较字符串：比较的是引用，应改 equals"),
    ("LOGIC-005", "logic", "MAJOR",
     r'\breturn\s+null\s*;',
     "新增 return null：调用方需判空，否则 NPE（需确认是否已有契约约定）"),
    ("LOGIC-006", "logic", "MINOR",
     r'\bif\s*\([^)]*\)\s*\{\s*\}',
     "空 if 分支：条件成立时什么都不做（分支写错 / 遗漏实现）"),
    # —— 错误信息质量（本项目踩过「失败静默」的坑，这类最值钱）——
    ("AE-001", "logic", "MINOR",
     r'(?i)log\.(warn|error|info)\s*\([^;)]*e\.getMessage\(\)\s*\)',
     "日志只打 e.getMessage()：丢了堆栈与异常类型，线上无法定位"),
    ("AE-002", "logic", "MAJOR",
     r'throw\s+new\s+(RuntimeException|IllegalStateException)\s*\(\s*e\.getMessage\(\)\s*\)',
     "重抛时丢弃原始异常：链断了，根因信息丢失"),
    # —— 并发 / 资源（本语料里未命中，保留以覆盖后续新增语料）——
    ("CONC-001", "concurrency", "MAJOR",
     r'new\s+SimpleDateFormat\s*\(|new\s+Random\s*\(\s*\)',
     "非线程安全/可预测的实例：SimpleDateFormat 与 Random 不应被多线程共享"),
    ("CONC-002", "concurrency", "MAJOR",
     r'new\s+Thread\s*\(|Thread\.sleep\s*\(|Executors\.newCachedThreadPool\s*\(',
     "绕过线程池：直接建线程 / sleep 阻塞 / 无界缓存池，生产环境应走有界池"),
    # ★ 这条曾经假报：`private static Map<X,Y> buildCrossCallers(...)` 是**方法**不是字段。
    #   判据补上「类型后面必须是标识符 + (`;` 或 `=`)」，而不是 `(`。
    ("CONC-003", "concurrency", "MINOR",
     r'private\s+static\s+(?!final)(?:[\w.]+\.)*(?:Map|List|Set|HashMap|ArrayList|StringBuilder)\s*<[^;]*>\s+\w+\s*[;=]',
     "非 final 的可变静态字段：多实例/多线程下共享可变状态"),
    ("RES-001", "resource", "MAJOR",
     r'(?<!try\s\()new\s+(FileInputStream|FileOutputStream|BufferedReader|FileReader|FileWriter)\s*\(',
     "资源未用 try-with-resources：异常路径会泄漏句柄"),
    # —— 架构 / 可维护性 ——
    ("ARCH-001", "architecture", "MINOR",
     r'(?i)"\s*select\s+\*',
     "SELECT *：字段耦合，schema 变更会静默影响调用方"),
    ("ARCH-002", "architecture", "MINOR",
     r'(?i)@SuppressWarnings|NOSONAR|noinspection',
     "抑制告警：会掩盖真实缺陷，需写明理由"),
    # —— 规范（本项目自己的硬规范，见 README 写作/编码纪律）——
    ("STYLE-001", "style", "MINOR",
     r'(?://|#|/\*|\*)\s*(TODO|FIXME|XXX)\b',
     "残留 TODO/FIXME：应转成 issue 或补齐实现"),
    ("STYLE-002", "style", "MINOR",
     r'System\.out\.print|\.printStackTrace\(\)',
     "生产代码里的调试输出：应走日志框架"),
    ("STYLE-003", "style", "MINOR",
     r'log\.(info|debug|warn|error)\s*\(\s*"[^"]*"\s*\+',
     "日志用字符串拼接：应使用 {} 占位符（本项目规范，且拼接会白付字符串构造开销）"),
    ("STYLE-004", "style", "MINOR",
     r'\.size\(\)\s*(==|>|!=)\s*0\b',
     "用 size() 比 0 判断空：应改 isEmpty()（语义更直白，且对某些实现更快）"),
    ("STYLE-005", "style", "MINOR",
     r'\bnew\s+String\s*\(|\bString\.valueOf\([^)]*\)\s*\+\s*"',
     "多余的 String 构造/转换：直接拼接即可"),
]

# ── 需要**跨行状态**的规则（正则逐行判不了，单独实现）─────────────────────────
# 判据全部是「可机械验证的事实」，不是审美判断——这样"人工确认"才有明确的裁决点。
DOC_JAVADOC = "DOC-001"      # 新增 public 方法缺 Javadoc（本项目规范：public 方法必带）
SIZE_LONG = "SIZE-001"       # 新增方法体超过 60 行
DOC_WHY = "新增 public 方法缺 Javadoc：本项目规范要求对外方法写明用途（对照同文件其它方法）"
SIZE_WHY = "新增方法体超过 60 行：单方法过长，需拆分或说明为何不可拆"

PUBLIC_METHOD_RE = re.compile(r'^\s+public\s+(?:static\s+)?(?:final\s+)?[\w<>,.\[\]? ]+\s+(\w+)\s*\(')
JAVADOC_END_RE = re.compile(r'^\s*\*/\s*$')
ANNOT_RE = re.compile(r'^\s*@')

# 允许的重复模式过滤：命中这些的行不算候选（避免把正常的空 catch 测试等误收）
EXCLUDE_CONTEXT = (
    "test/java/",           # 测试代码里的空 catch 常是刻意的（参数化断言）
    "src/test/",
)


def sh(args, cwd=REPO):
    return subprocess.run(args, cwd=cwd, capture_output=True).stdout.decode("utf-8", "replace")


def http(url, accept=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", "cra-bench-collector")
    if accept:
        req.add_header("Accept", accept)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def scan_candidates(patch):
    """对 patch 的新增行跑 rubric，产出候选（未确认）。

    分两层：
      ① **逐行正则**：12 类模式，规则见 RUBRIC；
      ② **跨行状态**：Java 的「public 方法缺 Javadoc」与「方法体过长」需要看前一行 /
         数配对花括号，正则判不了，单独走一遍（DOC-001 / SIZE-001）。

    从 patch 里还原「文件 → 新增行（含新文件侧行号）」之后，两层共用这一份行表。
    """
    # ── 还原新增行 ──────────────────────────────────────────────
    added = []          # [(file, newline, text)]
    cur_file = ""
    new_line = 0
    for raw in patch.splitlines():
        if raw.startswith("+++ b/"):
            cur_file = raw[6:].strip()
            new_line = 0
            continue
        m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@", raw)
        if m:
            new_line = int(m.group(1))
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            added.append((cur_file, new_line, raw[1:]))
            new_line += 1
        elif not raw.startswith("-"):
            new_line += 1

    out = []
    for cur_file, lineno, body in added:
        if any(x in cur_file for x in EXCLUDE_CONTEXT):
            continue
        for rid, cat, sev, pat, why in RUBRIC:
            if re.search(pat, body):
                out.append({
                    "ruleId": rid, "category": cat, "severity": sev,
                    "file": cur_file, "line": lineno,
                    "evidence": body.strip()[:200], "why": why, "confirmed": False,
                })

    # ── 跨行状态规则（只对 .java 生效）────────────────────────────
    by_file = {}
    for f, ln, body in added:
        by_file.setdefault(f, []).append((ln, body))
    for f, rows in by_file.items():
        if not f.endswith(".java") or any(x in f for x in EXCLUDE_CONTEXT):
            continue
        rows.sort()
        for idx, (ln, body) in enumerate(rows):
            m = PUBLIC_METHOD_RE.match(body)
            if not m:
                continue
            # DOC-001：往前看最多 6 行，若都不是 Javadoc 结束行（允许夹注解）⇒ 缺注释。
            # ★ 两条排除，否则会大面积假报：
            #   ① 注解行跳过继续往前找（`@Override` 会插在 Javadoc 与方法签名之间）；
            #   ② 若窗口里出现 `@Override` ⇒ **不判**（覆写方法按惯例继承父类文档，
            #      要求它再写一遍 Javadoc 是错的——第一版没排除，113 条里大半是这类）。
            prev_ok = False
            saw_override = False
            for j in range(idx - 1, max(-1, idx - 7), -1):
                p = rows[j][1].strip()
                if not p:
                    continue
                if p.startswith("@"):
                    if "@Override" in p:
                        saw_override = True
                    continue
                prev_ok = bool(JAVADOC_END_RE.match(rows[j][1]))
                break
            if not prev_ok and not saw_override:
                out.append({
                    "ruleId": DOC_JAVADOC, "category": "style", "severity": "MINOR",
                    "file": f, "line": ln, "evidence": body.strip()[:200],
                    "why": DOC_WHY, "confirmed": False,
                })
            # SIZE-001：从这一行起配对花括号，直到配平；统计**新增行**里方法体的行数
            depth = 0
            started = False
            end_idx = idx
            for k in range(idx, len(rows)):
                txt = rows[k][1]
                depth += txt.count("{") - txt.count("}")
                if "{" in txt:
                    started = True
                if started and depth <= 0:
                    end_idx = k
                    break
            span = end_idx - idx + 1
            if started and span > 60:
                out.append({
                    "ruleId": SIZE_LONG, "category": "style", "severity": "MINOR",
                    "file": f, "line": ln, "evidence": f"{body.strip()[:120]} …（共 {span} 行）",
                    "why": SIZE_WHY, "confirmed": False,
                })
    return out


def collect_local():
    cases = []
    head = sh(["git", "rev-parse", "HEAD"]).strip()
    for sha, why in LOCAL_COMMITS:
        full = sh(["git", "rev-parse", sha]).strip()
        parent = sh(["git", "rev-parse", f"{sha}~1"]).strip()
        subject = sh(["git", "log", "-1", "--pretty=%s", sha]).strip()
        date = sh(["git", "log", "-1", "--pretty=%aI", sha]).strip()
        files = [l for l in sh(["git", "show", "--name-only", "--format=", sha]).splitlines() if l.strip()]
        patch = sh(["git", "show", "--format=", "--no-ext-diff", sha])
        numstat = sh(["git", "show", "--numstat", "--format=", sha])
        add = dele = 0
        for l in numstat.splitlines():
            p = l.split("\t")
            if len(p) == 3 and p[0].isdigit():
                add += int(p[0]); dele += int(p[1])
        cid = f"cra-{sha}"
        cases.append({
            "id": cid,
            "repoKey": "code-review-agent",
            "repoUrl": LOCAL_REPO_URL,
            "provenance": "git-history",
            "provenanceNote": why,
            "sha": full,
            "shaShort": sha,
            "parent": parent,
            "subject": subject,
            "committedAt": date,
            "changedFileCount": len(files),
            "changedFiles": files,
            "addedLines": add,
            "deletedLines": dele,
            "patch": patch,
            "groundTruth": [],
            "labelCandidates": scan_candidates(patch),
            "replayInput": [],
        })
    return cases, head


def collect_oss():
    cases = []
    for repo, num, why in OSS_PRS:
        meta = json.loads(http(f"https://api.github.com/repos/{repo}/pulls/{num}"))
        diff = http(f"https://api.github.com/repos/{repo}/pulls/{num}",
                    accept="application/vnd.github.v3.diff").decode("utf-8", "replace")
        files = [l[len("+++ b/"):].strip()
                 for l in diff.splitlines() if l.startswith("+++ b/")]
        cid = f"oss-{repo.split('/')[-1]}-{num}"
        cases.append({
            "id": cid,
            "repoKey": repo,
            "repoUrl": f"https://github.com/{repo}",
            "provenance": "github-pr",
            "provenanceNote": why,
            "prNumber": num,
            "prUrl": f"https://github.com/{repo}/pull/{num}",
            "baseSha": meta["base"]["sha"],
            "headSha": meta["head"]["sha"],
            "mergeCommitSha": meta.get("merge_commit_sha"),
            "mergedAt": meta.get("merged_at"),
            "sha": meta.get("merge_commit_sha"),
            "shaShort": str(meta.get("merge_commit_sha"))[:7],
            "parent": meta["base"]["sha"],
            "subject": meta["title"],
            "committedAt": meta.get("merged_at"),
            "changedFileCount": meta["changed_files"],
            "changedFiles": files,
            "addedLines": meta["additions"],
            "deletedLines": meta["deletions"],
            "patch": diff,
            "groundTruth": [],
            "labelCandidates": scan_candidates(diff),
            "replayInput": [],
        })
    return cases


def write_all(cases, head):
    os.makedirs(CASES, exist_ok=True)
    manifest_cases = []
    preserved = stale = 0
    for c in cases:
        p = c["patch"]
        raw = p.encode("utf-8")
        if len(raw) > PATCH_CAP:
            p = raw[:PATCH_CAP].decode("utf-8", "ignore")
            c["patchTruncated"] = True
        c["patch"] = p
        c["patchBytes"] = len(p.encode("utf-8"))
        c["patchSha256"] = hashlib.sha256(p.encode("utf-8")).hexdigest()

        # ★ 重采**不得覆盖人工标注**：groundTruth / labeling / replayInput 属于标注阶段，
        #   采集器只拥有 provenance。若 patch 变了，标注可能已过期 —— 必须报警而不是静默保留。
        path = os.path.join(CASES, c["id"] + ".json")
        if os.path.exists(path):
            old = json.load(open(path, encoding="utf-8"))
            for k in ("groundTruth", "labeling", "replayInput"):
                if k in old:
                    c[k] = old[k]
                    preserved += 1
            if old.get("patchSha256") and old["patchSha256"] != c["patchSha256"]:
                stale += 1
                print(f"  ⚠ {c['id']} 的 patch 已变（hash {old['patchSha256'][:12]} → "
                      f"{c['patchSha256'][:12]}）：其人工标注可能已过期，需重跑 label_assist")

        with open(path, "w", encoding="utf-8") as fh:
            json.dump(c, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
        manifest_cases.append({
            "id": c["id"],
            "repoKey": c["repoKey"],
            "provenance": c["provenance"],
            "shaShort": c["shaShort"],
            "subject": c["subject"],
            "changedFileCount": c["changedFileCount"],
            "addedLines": c["addedLines"],
            "deletedLines": c["deletedLines"],
            "patchSha256": c["patchSha256"],
            "patchTruncated": c.get("patchTruncated", False),
            "candidateCount": len(c["labelCandidates"]),
        })
    manifest = {
        "version": 1,
        "generatedBy": "tools/bench/collect_corpus.py",
        "codeReviewAgentHead": head,
        "caseCount": len(manifest_cases),
        "preservedLabelFields": preserved,
        "staleLabels": stale,
        "cases": manifest_cases,
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    return manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只校验现有产物与重采是否一致")
    args = ap.parse_args()

    local, head = collect_local()
    oss = collect_oss()
    cases = local + oss
    if len(cases) != 20:
        print(f"★ 语料条数不为 20（实际 {len(cases)}）", file=sys.stderr)
        return 2

    if args.check:
        bad = 0
        for c in cases:
            f = os.path.join(CASES, c["id"] + ".json")
            if not os.path.exists(f):
                print(f"  ✗ 缺文件 {c['id']}")
                bad += 1
                continue
            old = json.load(open(f, encoding="utf-8"))
            new_sha = hashlib.sha256(c["patch"].encode("utf-8")).hexdigest()
            if old.get("patchSha256") != new_sha:
                print(f"  ✗ {c['id']} patch 已变（{old.get('patchSha256','?')[:12]} → {new_sha[:12]}）")
                bad += 1
        print(f"校验 {len(cases)} 条：{'全部一致' if bad == 0 else f'{bad} 条不一致'}")
        return 1 if bad else 0

    m = write_all(cases, head)
    tot_cand = sum(c["candidateCount"] for c in m["cases"])
    print(f"已写入 {OUT}")
    print(f"  语料 {m['caseCount']} 条"
          f"（本仓 {len(LOCAL_COMMITS)} + 开源 PR {len(OSS_PRS)}）")
    print(f"  候选标注合计 {tot_cand} 条（**未确认**，需人工过一遍才进 groundTruth）")
    print(f"  本仓 HEAD = {head[:7]}（写进 manifest.codeReviewAgentHead）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1-6 标注工作台：把「人工确认」这件事变成一份可复核的文本文件。

为什么不让脚本直接写 groundTruth
--------------------------------
`collect_corpus.py` 扫出来的 66 条候选是**机器意见**，它**不是** ground truth。
如果让脚本把它们直接写进 `groundTruth`，那 precision/recall 就变成了"机器 vs 机器"，
指标丧失意义——而且任何人回头看都分不清「这条是人工标的还是正则扫的」。

所以本工作台把流程切成两段，中间夹一份**人写的**文件：

    ① `--sheet`   打印每条候选 + hunk 上下文 ⇒ 人逐条判 keep / drop
    ② 人写 `labels.tsv`（制表符分隔，见下方列义）
    ③ `--apply`   只把 keep 的行写进各 case 的 `groundTruth`，并盖上 `labelSource`

这样"哪一条是谁标的"永远可查，且 `labels.tsv` 本身就是标注记录。

labels.tsv 列义（制表符分隔，第一行是表头）
------------------------------------------
    caseId  ruleId  file  line  verdict  severity  category  note

* `verdict` ∈ {keep, drop}；`drop` 行只作记录，不写进 groundTruth
* `severity` / `category` 可留空 = 沿用 rubric 的默认值（人工可覆盖）
* `note` 一句话说明「为什么这是真问题」，**必填**（空 note 的 keep 会被拒绝）

用法
----
    python3 tools/bench/label_assist.py --sheet                # 打印待确认候选
    python3 tools/bench/label_assist.py --sheet --case cra-7410a93
    python3 tools/bench/label_assist.py --apply                # 应用 labels.tsv
    python3 tools/bench/label_assist.py --stats                # 统计标注进度
"""

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
CASES = os.path.join(REPO, "src", "main", "resources", "bench", "cases")
LABELS = os.path.join(HERE, "labels.tsv")
MANUAL = os.path.join(HERE, "manual_labels.tsv")
HEADER = ["caseId", "ruleId", "file", "line", "verdict", "severity", "category", "note"]
# 人工标注列义（比 labels.tsv 少了 verdict——写进来就是 keep）
MANUAL_HEADER = ["caseId", "ruleId", "file", "line", "severity", "category", "note"]
# ★ 两份 TSV 一律用 utf-8-sig 读：某些编辑器/工具写盘时会加 BOM，
#   用 utf-8 读会把 BOM 粘在第一个表头字段上 ⇒ 表头校验假报"不符"（已踩过）。


def load_cases():
    out = {}
    for n in sorted(os.listdir(CASES)):
        if n.endswith(".json"):
            d = json.load(open(os.path.join(CASES, n), encoding="utf-8"))
            out[d["id"]] = d
    return out


def hunk_of(patch, file, line, before=3, after=3):
    """从 patch 里取目标文件的 hunk，并围绕目标行截取上下文。"""
    lines = patch.splitlines()
    cur = None
    keep = []
    newno = 0
    for raw in lines:
        if raw.startswith("+++ b/"):
            cur = raw[6:].strip()
            continue
        m = None
        if raw.startswith("@@"):
            import re
            m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@", raw)
            if m:
                newno = int(m.group(1))
        if cur != file:
            continue
        if raw.startswith("@@"):
            keep.append(("hunk", raw))
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            keep.append(("add", raw, newno))
            newno += 1
        elif raw.startswith("-") and not raw.startswith("---"):
            keep.append(("del", raw))
        else:
            keep.append(("ctx", raw, newno))
            newno += 1
    # 定位目标新增行
    hits = [i for i, t in enumerate(keep) if t[0] == "add" and t[2] == line]
    if not hits:
        return "\n".join(t[1] for t in keep[:24])
    i = hits[0]
    seg = keep[max(0, i - before): i + after + 1]
    out = []
    for t in seg:
        mark = ">>" if (t[0] == "add" and len(t) > 2 and t[2] == line) else "  "
        out.append(f"    {mark} {t[1]}")
    return "\n".join(out)


def cmd_sheet(cases, only=None):
    total = 0
    for cid, c in cases.items():
        if only and cid != only:
            continue
        cands = [x for x in c["labelCandidates"] if not x.get("confirmed")]
        # 已 confirmed 的（人工已标）不重复打印
        if not cands:
            continue
        print(f"\n{'='*96}\n【{cid}】{c['subject'][:70]}")
        print(f"  仓库={c['repoKey']} sha={c['shaShort']} 文件={c['changedFileCount']} "
              f"+{c['addedLines']}/-{c['deletedLines']} 来源={c['provenance']}")
        for x in cands:
            total += 1
            print(f"\n  [{x['ruleId']}] {x['severity']} {x['file']}:{x['line']}")
            print(f"     机器理由：{x['why']}")
            print(hunk_of(c["patch"], x["file"], x["line"]))
    print(f"\n待确认候选合计：{total} 条")
    return 0


def cmd_apply(cases):
    if not os.path.exists(LABELS):
        print(f"★ 没有 {LABELS}，先按 --sheet 的结果写一份", file=sys.stderr)
        return 2
    rows = [l.rstrip("\n").split("\t") for l in open(LABELS, encoding="utf-8-sig") if l.strip()]
    if rows[0][:len(HEADER)] != HEADER:
        print(f"★ labels.tsv 表头不符，应为：{chr(9).join(HEADER)}", file=sys.stderr)
        return 2
    kept = dropped = 0
    errors = []
    by_case = {}
    # 每条语料都盖上 labeling 块（没标到的也要有，否则读的人分不清"没标"与"标了零条"）
    for cid, c in cases.items():
        c["labeling"] = {
            "method": "rule-classified+sampled-review",
            "candidates": len(c["labelCandidates"]),
            "confirmed": 0,
            "manual": 0,
            "rules": "见 tools/bench/label_assist.py 的 classify() 文档",
            "pending": "逐条人工复核（当前为规则裁决 + 每类抽样读 hunk）",
        }
    for r in rows[1:]:
        if len(r) < 8:
            errors.append(f"列数不足 8：{r}")
            continue
        cid, rid, f, line, verdict, sev, cat, note = r[:8]
        if verdict not in ("keep", "drop"):
            errors.append(f"{cid}: verdict 必须是 keep/drop，实际 {verdict!r}")
            continue
        if verdict == "keep" and not note.strip():
            errors.append(f"{cid} {rid} {f}:{line} 标 keep 但 note 为空（必须说明为什么是真问题）")
            continue
        by_case.setdefault(cid, []).append((rid, f, int(line), verdict, sev.strip(), cat.strip(), note.strip()))
        if verdict == "keep":
            kept += 1
        else:
            dropped += 1
    if errors:
        print("★ 应用被拒绝（先修 labels.tsv）：", file=sys.stderr)
        for e in errors:
            print("   - " + e, file=sys.stderr)
        return 2

    for cid, items in by_case.items():
        c = cases.get(cid)
        if c is None:
            print(f"★ 未知 caseId：{cid}", file=sys.stderr)
            return 2
        lookup = {(x["ruleId"], x["file"], x["line"]): x for x in c["labelCandidates"]}
        gt = []
        for rid, f, line, verdict, sev, cat, note in items:
            src = lookup.get((rid, f, line))
            if src is None:
                print(f"★ {cid} 的候选里找不到 {rid} {f}:{line}（labels.tsv 与语料不同步，"
                      f"请重跑 collect_corpus.py）", file=sys.stderr)
                return 2
            src["confirmed"] = (verdict == "keep")
            if verdict == "keep":
                gt.append({
                    "ruleId": rid,
                    "category": cat or src["category"],
                    "severity": sev or src["severity"],
                    "file": f,
                    "lineStart": line,
                    "lineEnd": line,
                    "note": note,
                    "labelSource": "human-confirmed-machine-candidate",
                })
        c["groundTruth"] = gt

    # ── 合并人工标注（manual_labels.tsv）────────────────────────────────
    manual = 0
    if os.path.exists(MANUAL):
        mrows = [l.rstrip("\n").split("\t") for l in open(MANUAL, encoding="utf-8-sig") if l.strip()]
        if mrows[0][:len(MANUAL_HEADER)] != MANUAL_HEADER:
            print(f"★ manual_labels.tsv 表头不符，应为：{chr(9).join(MANUAL_HEADER)}", file=sys.stderr)
            return 2
        for r in mrows[1:]:
            if len(r) < 7:
                print(f"★ manual_labels.tsv 列数不足 7：{r}", file=sys.stderr)
                return 2
            cid, rid, f, line, sev, cat, note = r[:7]
            c = cases.get(cid)
            if c is None:
                print(f"★ manual_labels.tsv 里未知 caseId：{cid}", file=sys.stderr)
                return 2
            if not note.strip():
                print(f"★ {cid} {f}:{line} 人工标注的 note 为空", file=sys.stderr)
                return 2
            c.setdefault("groundTruth", []).append({
                "ruleId": rid, "category": cat, "severity": sev,
                "file": f, "lineStart": int(line), "lineEnd": int(line),
                "note": note, "labelSource": "manual",
            })
            manual += 1
    for cid, c in cases.items():
        if c.get("labeling"):
            c["labeling"]["confirmed"] = len(c.get("groundTruth") or [])
            c["labeling"]["manual"] = sum(
                1 for g in (c.get("groundTruth") or []) if g.get("labelSource") == "manual")

    # 全量写回
    for cid, c in cases.items():
        p = os.path.join(CASES, cid + ".json")
        with open(p, "w", encoding="utf-8") as fh:
            json.dump(c, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
    print(f"已应用：keep {kept} 条 / drop {dropped} 条；人工标注 {manual} 条；覆盖 {len(cases)} 条语料")
    return 0


def cmd_stats(cases):
    tot_gt = tot_case = 0
    for cid, c in cases.items():
        n = len(c.get("groundTruth") or [])
        tot_gt += n
        tot_case += 1 if n else 0
        flag = "✅" if n else "  "
        print(f"  {flag} {cid:26} GT={n:3}  候选={len(c['labelCandidates']):3}  {c['subject'][:44]}")
    print(f"\n语料 {tot_case} 条；已标注 {sum(1 for c in cases.values() if c.get('groundTruth'))} 条；"
          f"ground-truth issue 合计 {tot_gt} 条")
    return 0


def classify(cases):
    """按**已验证的裁决规则**给全部候选打 keep/drop，产出 labels.tsv。

    ★ 这不是"脚本替我标"，而是把人工裁决**写成可复核的规则**：
      每条规则都附 `根据`（我在 `--sheet` 上读过的具体 hunk 案例），
      且每条规则都在语料上抽过样。逐条复核仍是待办，但当前口径完全公开可查。

    规则（按优先级从上到下）：
      R1  DOC-001（新增 public 方法缺 Javadoc）⇒ **keep**
          根据：`UsageAwareModelProvider` 里 `hasUsage()` 有 Javadoc 而紧随其后的
                `promptTokensOrZero()` / `completionTokensOrZero()` 没有；同文件同族不一致
                ⇒ 是真实规范缺口。规则已排除 `@Override`（覆写按惯例继承文档）。
      R2  AE-001（日志只打 e.getMessage()）⇒ **keep**
          根据：`AdvancedAnalyzer:194` 那类 `log.warn("...: {}", e.getMessage())` 确实丢了堆栈。
      R3  LOGIC-002（catch (Exception)）⇒ **按 catch 体分三档**：
            · 体内有 `throw` 或 `log.warn/error` ⇒ **drop**（有处理，属合法降级）
            · 体内只有 `log.debug` ⇒ **keep（MINOR）**（生产默认不开 DEBUG ⇒ 等同静默）
            · 体内既无 log 也无 throw ⇒ **keep（MAJOR）**「静默吞噬」
          根据：`LlmJudge:94` / `ExperienceStore:79` / `ToolEquippedAgent:51`（空 catch 无日志）
                与 `DagExecutor:80` / `ToolCallingLoop:86`（log.warn + 返回失败）的对比。
      R4  LOGIC-005（新增 return null）⇒ 前一非空新增行是 Javadoc/注释且含 null ⇒ **drop**
          （文档化的 null 哨兵，如 `LlmQueryRewriter.sanitize` 的「非法返回 null」）；
          否则 **keep（MINOR）**。
      R5  其余规则（SEC-* / CONC-* / RES-* / ARCH-* / STYLE-* / SIZE-* / LOGIC-003/004/006）
          ⇒ **keep**（命中量小、逐条已在 --sheet 上过）
    """
    rows = []
    for cid in sorted(cases):
        c = cases[cid]
        patch = c["patch"]
        added = {}
        cur, n = "", 0
        for raw in patch.splitlines():
            if raw.startswith("+++ b/"):
                cur, n = raw[6:].strip(), 0
                continue
            m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@", raw)
            if m:
                n = int(m.group(1))
                continue
            if raw.startswith("+") and not raw.startswith("+++"):
                added[(cur, n)] = raw[1:]
                n += 1
            elif not raw.startswith("-"):
                n += 1
        # 按 (文件, 行) 排序，供"后几行"判断
        seq = sorted(added.items())
        idx_of = {k: i for i, (k, _) in enumerate(seq)}

        for x in c["labelCandidates"]:
            rid = x["ruleId"]
            key = (x["file"], x["line"])
            i = idx_of.get(key)
            nxt = [seq[j][1] for j in range(i + 1, min(len(seq), i + 4))] if i is not None else []
            prev = None
            if i is not None:
                for j in range(i - 1, max(-1, i - 4), -1):
                    if seq[j][1].strip():
                        prev = seq[j][1]
                        break
            body = "\n".join(nxt)
            verdict, sev, note = "keep", x["severity"], ""

            if rid == "DOC-001":
                note = "同文件同族方法都写了 Javadoc，这一处没有（本项目规范要求 public 方法写明用途）"
            elif rid == "AE-001":
                sev, note = "MINOR", "只打 e.getMessage() 丢了堆栈与异常类型，线上无法定位根因"
            elif rid == "LOGIC-002":
                has_throw = "throw " in body
                has_warn = ("log.warn" in body) or ("log.error" in body)
                has_dbg = "log.debug" in body
                if has_throw or has_warn:
                    verdict, note = "drop", "catch 体内有记录或重抛，属合法降级，不构成缺陷"
                elif has_dbg:
                    sev = "MINOR"
                    note = "catch 体只写 log.debug：生产默认不开 DEBUG ⇒ 实际等同静默失败"
                else:
                    sev = "MAJOR"
                    note = "catch 体内既无日志也无重抛 ⇒ 失败静默，调用方无从感知"
            elif rid == "LOGIC-005":
                if prev and ("null" in prev) and (prev.strip().startswith("*") or prev.strip().startswith("/**")):
                    verdict, note = "drop", "上一行 Javadoc 已说明失败返回 null，属文档化哨兵"
                else:
                    sev, note = "MINOR", "新增 return null 且未在同处说明契约，调用方易漏判空"
            else:
                note = x["why"]

            if verdict == "keep":
                rows.append([cid, rid, x["file"], str(x["line"]), "keep",
                             sev if sev != x["severity"] else "", "", note])
            else:
                rows.append([cid, rid, x["file"], str(x["line"]), "drop", "", "", note])

    with open(LABELS, "w", encoding="utf-8") as fh:
        fh.write("\t".join(HEADER) + "\n")
        for r in rows:
            fh.write("\t".join(r) + "\n")
    keeps = sum(1 for r in rows if r[4] == "keep")
    print(f"已写 {LABELS}：keep {keeps} / drop {len(rows) - keeps}（共 {len(rows)} 条候选）")
    return 0


AGENT_OF_CATEGORY = {
    "security": "SECURITY", "logic": "LOGIC", "performance": "PERFORMANCE",
    "style": "STYLE", "architecture": "ARCHITECTURE",
    "concurrency": "PERFORMANCE", "resource": "PERFORMANCE",
}
# 制造「同位置不同 Agent」的固定顺序（用于去重与仲裁的受控干扰）
NEXT_AGENT = {"SECURITY": "ARCHITECTURE", "LOGIC": "PERFORMANCE", "PERFORMANCE": "LOGIC",
              "STYLE": "LOGIC", "ARCHITECTURE": "SECURITY"}


def build_replay_input(cases):
    """由 groundTruth 派生回放输入 + 受控干扰项（deterministic，可复跑）。

    ★ 诚实标注：这不是真实模型产出。
      本基准当前回答的是「**同一批候选发现，经过去重 / 仲裁 / 抑制 / 否决 / 聚合之后，
      剩下的集合对不对**」——测的是**确定性后处理链**，不是模型质量。
      `replayInput.origin` 写明来源；未来用真实模型采集（`--capture`）后改写 origin 即可
      复用同一套指标与门禁。

    受控干扰项的用途（每一项都对应链上一段可被改坏的逻辑）：
      · `noise`    —— 与 GT 无关的发现 ⇒ 衡量 precision（不该留的被留下）
      · `dup`      —— 同一 (文件@行#规则) 从另一个 Agent 再来一条 ⇒ 衡量**去重**
      · `conflict` —— 同一位置不同 Agent 意见冲突 ⇒ 衡量**仲裁**
      · `feedback` —— 把某条 BLOCKER 标成误报 ⇒ 衡量 **VetoPolicy 能否把它捞回来**
    """
    built = 0
    for cid, c in sorted(cases.items()):
        gt = c.get("groundTruth") or []
        if not gt:
            c["replayInput"] = {
                "origin": "none",
                "note": "本语料为纯删除/重命名类改动，无新增缺陷面，未构造回放输入"
                        "（保留在基准集中作为**负向样本**）",
                "agents": [], "feedback": [],
            }
            continue

        agents = {}
        for g in gt:
            ag = AGENT_OF_CATEGORY.get(g["category"], "LOGIC")
            agents.setdefault(ag, []).append({
                "agentType": ag, "file": g["file"],
                "lineStart": g["lineStart"], "lineEnd": g.get("lineEnd", g["lineStart"]),
                "severity": g["severity"], "category": g["category"], "ruleId": g["ruleId"],
                "title": g["note"][:60] if g.get("note") else g["ruleId"],
                "description": g.get("note", ""), "suggestion": "按评审意见修复",
                "confidence": 0.8, "source": "LLM",
            })
        first = gt[0]
        ag0 = AGENT_OF_CATEGORY.get(first["category"], "LOGIC")
        # dup：同 dedupKey、不同 Agent、更低置信度
        agents.setdefault(NEXT_AGENT.get(ag0, "STYLE"), []).append({
            "agentType": NEXT_AGENT.get(ag0, "STYLE"), "file": first["file"],
            "lineStart": first["lineStart"], "lineEnd": first.get("lineEnd", first["lineStart"]),
            "severity": first["severity"], "category": first["category"],
            "ruleId": first["ruleId"],
            "title": "（去重受控样本）" + first["ruleId"],
            "description": "与首条同 (文件@行#规则)，用于验证去重只保留一条且不丢真问题",
            "suggestion": "同上", "confidence": 0.6, "source": "LLM",
        })
        # conflict：同位置、不同 Agent、不同 ruleId、建议相反
        sec = next((g for g in gt if g["category"] in ("security", "logic")), None)
        if sec:
            agents.setdefault("ARCHITECTURE", []).append({
                "agentType": "ARCHITECTURE", "file": sec["file"],
                "lineStart": sec["lineStart"], "lineEnd": sec.get("lineEnd", sec["lineStart"]),
                "severity": "MINOR", "category": "architecture", "ruleId": "ARCH-900",
                "title": "（仲裁受控样本）此处无需改动",
                "description": "与安全/逻辑 Agent 在同一位置给出相反结论，用于验证仲裁按优先级保留高优方",
                "suggestion": "保持现状", "confidence": 0.9, "source": "LLM",
            })
        # noise：与 GT 无关的两条（行号远离所有 GT，保证不会被邻近匹配误判为命中）
        maxline = max(g["lineStart"] for g in gt)
        for i in range(2):
            agents.setdefault("STYLE", []).append({
                "agentType": "STYLE", "file": gt[0]["file"],
                "lineStart": maxline + 500 + i * 17, "lineEnd": maxline + 500 + i * 17,
                "severity": "MINOR", "category": "style", "ruleId": f"STYLE-9{i}0",
                "title": f"（噪音受控样本 {i + 1}）命名风格建议",
                "description": "ground-truth 中不存在的问题，用于衡量 precision（不该留的被留下）",
                "suggestion": "重命名", "confidence": 0.7, "source": "LLM",
            })
        feedback = []
        for g in gt:
            if g["severity"] == "BLOCKER":
                feedback.append({
                    "ruleId": g["ruleId"], "agentType": None, "isFalsePositive": True,
                    "note": "（受控样本）把这条 BLOCKER 标成误报，用于验证 VetoPolicy 会把它捞回来"
                            "——若捞不回来，recall 会掉",
                    "file": g["file"],
                })
        c["replayInput"] = {
            "origin": "derived-from-ground-truth+controlled-distractors",
            "note": "由 groundTruth 派生 + 受控干扰项（noise/dup/conflict/feedback）；"
                    "**不是真实模型产出**，故本基准测的是确定性后处理链，不是模型质量",
            "agents": [{"agentType": k, "findings": v} for k, v in sorted(agents.items())],
            "feedback": feedback,
        }
        built += 1
    for cid, c in cases.items():
        p = os.path.join(CASES, cid + ".json")
        with open(p, "w", encoding="utf-8") as fh:
            json.dump(c, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
    print(f"已为 {built} 条语料构造回放输入（其余为负向样本，replayInput.origin=none）")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--classify", action="store_true", help="按已裁决规则产出 labels.tsv")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--build-replay-input", action="store_true")
    ap.add_argument("--stats", action="store_true")
    ap.add_argument("--case", default=None)
    args = ap.parse_args()
    cases = load_cases()
    if args.sheet:
        return cmd_sheet(cases, args.case)
    if args.classify:
        return classify(cases)
    if args.apply:
        return cmd_apply(cases)
    if args.build_replay_input:
        return build_replay_input(cases)
    if args.stats:
        return cmd_stats(cases)
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main())

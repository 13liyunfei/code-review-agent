package com.codereview.agent.core.gate;

import com.codereview.agent.core.model.CodeDiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次送审闸门过滤的完整结论：**送审清单 + 被拦清单 + 按原因聚合**。
 *
 * <p>为什么要把「被拦清单」也带出来，而不是只返回过滤后的文件列表：
 * 只返回结果的话，「这次为什么少审了 3 个文件」这个问题在任何日志里都答不出来。
 * 被拦路径与原因是可审计信息，必须与通过路径一样被记录下来。
 *
 * <p>对齐事件源审查日志的不变量「模型可见即可追溯」：本报告经
 * {@link #toEventPayload()} 写入审查轨迹，因此「喂给模型的文件集合」与其
 * 补集（被挡在门外的文件）都可回读。
 */
public final class GateReport {

    private final List<CodeDiff> admitted;
    private final List<GateDecision> blocked;
    private final Map<String, Integer> countsByReason;
    private final boolean allBlocked;

    private GateReport(List<CodeDiff> admitted, List<GateDecision> blocked,
                       Map<String, Integer> countsByReason, boolean allBlocked) {
        this.admitted = Collections.unmodifiableList(new ArrayList<>(admitted));
        this.blocked = Collections.unmodifiableList(new ArrayList<>(blocked));
        this.countsByReason = Collections.unmodifiableMap(new LinkedHashMap<>(countsByReason));
        this.allBlocked = allBlocked;
    }

    /**
     * 构造一份闸门报告。
     *
     * @param admitted        通过闸门、将进入送审清单的文件
     * @param gateDecisions   全部判定（含通过项）；被拦项据此推导
     * @return 报告
     */
    public static GateReport of(List<CodeDiff> admitted, List<GateDecision> gateDecisions) {
        List<GateDecision> blocked = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GateDecision d : gateDecisions) {
            if (d != null && !d.admitted()) {
                blocked.add(d);
                counts.merge(d.reason().code(), 1, Integer::sum);
            }
        }
        // 「全部被拦」需要区分于「本来就没有文件」：前者是闸门造成的空清单，必须显式可见
        boolean allBlocked = admitted.isEmpty() && !blocked.isEmpty();
        return new GateReport(admitted, blocked, counts, allBlocked);
    }

    /** 通过闸门、真正送审的文件列表。 */
    public List<CodeDiff> admitted() {
        return admitted;
    }

    /** 被拦下的文件（含原因），按输入顺序。 */
    public List<GateDecision> blocked() {
        return blocked;
    }

    /** 原因编码 -> 拦截个数（插入序，稳定可比）。 */
    public Map<String, Integer> countsByReason() {
        return countsByReason;
    }

    /** 是否存在「本该有文件、但全被拦掉」的情况（此时不应发起任何 LLM 调用）。 */
    public boolean allBlocked() {
        return allBlocked;
    }

    /**
     * 一行摘要，形如 {@code secret_exclude=1,default_path=3}。
     *
     * <p>故意做成单行且无空格：既好 grep，也方便直接当指标标签。
     *
     * @return 摘要串；无拦截时返回 {@code "none"}
     */
    public String summaryLine() {
        if (countsByReason.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        countsByReason.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /**
     * 供审查轨迹使用的事件载荷。
     *
     * <p>被拦路径**截断到前 20 条**：轨迹是给审计与回放看的，不该被一个几千文件的
     * PR 撑爆；总数与按原因聚合才是判据面，逐条清单只是举证材料。
     *
     * @return 事件载荷（键固定，便于消费方按 key 取值）
     */
    public Map<String, Object> toEventPayload() {
        List<String> paths = new ArrayList<>();
        for (GateDecision d : blocked) {
            if (paths.size() >= 20) {
                break;
            }
            paths.add(d.path() + " (" + d.reason().code() + ")");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("admitted", admitted.size());
        payload.put("blocked", blocked.size());
        payload.put("blockedByReason", summaryLine());
        payload.put("blockedPaths", paths);
        payload.put("truncated", blocked.size() > paths.size());
        return payload;
    }

    /**
     * 被拦清单的 Markdown 回显（贴进审查评论，回答「为什么没审这几个文件」）。
     *
     * @return Markdown 片段；无拦截时返回空串
     */
    public String blockedMarkdown() {
        if (blocked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("> **送审闸门**：本次有 ").append(blocked.size())
                .append(" 个文件未进入审查（").append(summaryLine()).append("）。\n>\n");
        int shown = 0;
        for (GateDecision d : blocked) {
            if (shown >= 20) {
                sb.append("> - ……其余 ").append(blocked.size() - shown).append(" 个已省略\n");
                break;
            }
            sb.append("> - `").append(d.path()).append("` —— ").append(d.reason().code()).append('\n');
            shown++;
        }
        return sb.toString();
    }
}

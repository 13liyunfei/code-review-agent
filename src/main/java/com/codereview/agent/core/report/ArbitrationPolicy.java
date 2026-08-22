package com.codereview.agent.core.report;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;

import java.util.Map;

/**
 * 冲突仲裁策略（见文档“冲突解决：按优先级规则或调用高级模型裁决”）。
 *
 * <p>当不同 Agent 在同一代码位置给出<b>相互冲突</b>的建议时，依据固定优先级权重裁决：
 * <pre>
 *   安全(SECURITY)=100 &gt; 逻辑(LOGIC)=90 &gt; 性能(PERFORMANCE)=70
 *      &gt; 架构(ARCHITECTURE)=60 &gt; 风格(STYLE)=10
 * </pre>
 * 高优先级 Agent 的意见胜出，低优先级意见被记录为“已裁决覆盖”。
 */
public final class ArbitrationPolicy {

    /** 各 Agent 类型的冲突仲裁优先级权重（数值越大越优先）。 */
    private static final Map<AgentType, Integer> PRIORITY = Map.of(
            AgentType.SECURITY, 100,
            AgentType.LOGIC, 90,
            AgentType.PERFORMANCE, 70,
            AgentType.ARCHITECTURE, 60,
            AgentType.STYLE, 10
    );

    private ArbitrationPolicy() {
    }

    /**
     * 获取 Agent 类型的仲裁优先级权重。
     *
     * @param type Agent 类型
     * @return 权重（未知类型返回 0）
     */
    public static int priorityOf(AgentType type) {
        return PRIORITY.getOrDefault(type, 0);
    }

    /**
     * 判断两条发现是否构成“冲突”，需同时满足：
     * <ol>
     *   <li>来自<b>不同</b> Agent 类型；</li>
     *   <li>位于<b>同一文件</b>且行区间<b>重叠</b>；</li>
     *   <li>修复<b>建议文本不同</b>（语义冲突，如“内联” vs “拆函数”）。</li>
     * </ol>
     *
     * @param a 发现 a
     * @param b 发现 b
     * @return 是否冲突
     */
    public static boolean isConflict(Finding a, Finding b) {
        if (a == b || a.agentType() == b.agentType()) {
            return false;
        }
        if (!a.file().equals(b.file())) {
            return false;
        }
        if (!linesOverlap(a, b)) {
            return false;
        }
        String sa = normalize(a.suggestion());
        String sb = normalize(b.suggestion());
        return !sa.equals(sb);
    }

    /**
     * 行区间重叠判定（lineStart=0 视为“非具体行”，不参与精准重叠，视为不冲突）。
     */
    private static boolean linesOverlap(Finding a, Finding b) {
        if (a.lineStart() <= 0 || b.lineStart() <= 0) {
            // 非具体行：仅当两者都在非具体行且同一文件才视为重叠（极少触发）
            return a.lineStart() <= 0 && b.lineStart() <= 0;
        }
        int aEnd = Math.max(a.lineStart(), a.lineEnd());
        int bEnd = Math.max(b.lineStart(), b.lineEnd());
        return a.lineStart() <= bEnd && b.lineStart() <= aEnd;
    }

    /**
     * 取冲突中胜出的 Agent 类型（优先级高者胜；相等时按严重级别，再按置信度）。
     */
    public static AgentType winner(Finding a, Finding b) {
        int pa = priorityOf(a.agentType());
        int pb = priorityOf(b.agentType());
        if (pa != pb) {
            return pa > pb ? a.agentType() : b.agentType();
        }
        if (a.severity().getPriority() != b.severity().getPriority()) {
            return a.severity().getPriority() > b.severity().getPriority()
                    ? a.agentType() : b.agentType();
        }
        return a.confidence() >= b.confidence() ? a.agentType() : b.agentType();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}

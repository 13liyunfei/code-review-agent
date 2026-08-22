package com.codereview.agent.core.history;

import com.codereview.agent.core.model.Severity;

/**
 * 单条发现的轻量摘要（用于跨次审查对比，避免持久化整条 Finding）。
 *
 * @param ruleId     规则 ID
 * @param agentType  来源 Agent 类型
 * @param file       文件
 * @param lineStart  起始行
 * @param lineEnd    结束行
 * @param severity   严重级别
 * @param title      问题标题
 */
public record FindingSummary(String ruleId, String agentType, String file,
                            int lineStart, int lineEnd, Severity severity, String title) {

    /**
     * 去重键：与 {@code Finding#dedupKey()} 保持一致，便于前后两次审查对齐。
     *
     * @return 去重键
     */
    public String dedupKey() {
        return file + "@" + lineStart + "-" + lineEnd + "#" + ruleId;
    }
}

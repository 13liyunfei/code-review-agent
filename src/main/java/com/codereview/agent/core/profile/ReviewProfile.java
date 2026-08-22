package com.codereview.agent.core.profile;

import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;

import java.util.List;

/**
 * 审查强度 Profile（可热切换，对齐 codex 的暴露面控制思路）。
 *
 * <p>同一套审查 Agent 在不通场景下应输出不同的「响度」：
 * <ul>
 *   <li>{@link #STRICT}（严格）：仅剔除 Info 级噪声，保留 Minor 及以上 —— CI 合并门禁场景；</li>
 *   <li>{@link #ADVISORY}（默认，建议）：只保留 Major 及以上，Minor / Info 过滤 —— 日常审查，聚焦高价值问题；</li>
 *   <li>{@link #SUGGEST}（仅建议）：全部保留，供人工逐条权衡 —— 上线前兜底 / 新手仓库。</li>
 * </ul>
 *
 * <p>配置：{@code review.profile=STRICT|ADVISORY|SUGGEST}（默认 {@code ADVISORY}），
 * 由 Coordinator 在聚合去重、仲裁、抑制<b>之后</b>作用于最终发现列表，不影响内部统计口径。
 */
public enum ReviewProfile {

    /** 严格模式：保留 Minor 及以上（仅过滤 Info 噪声）。 */
    STRICT {
        @Override
        public List<Finding> apply(List<Finding> findings) {
            return findings.stream()
                    .filter(f -> f.severity() != Severity.INFO)
                    .toList();
        }
    },

    /** 建议模式（默认）：只保留 Major 及以上，聚焦高价值问题。 */
    ADVISORY {
        @Override
        public List<Finding> apply(List<Finding> findings) {
            return findings.stream()
                    .filter(f -> f.severity() == Severity.BLOCKER || f.severity() == Severity.MAJOR)
                    .toList();
        }
    },

    /** 仅建议模式：全部保留，供人工逐条权衡。 */
    SUGGEST {
        @Override
        public List<Finding> apply(List<Finding> findings) {
            return findings;
        }
    };

    /**
     * 按本 Profile 的强度策略过滤 / 保留发现。
     *
     * @param findings 聚合后的最终发现（已去重、仲裁、抑制）
     * @return 过滤后的发现列表
     */
    public abstract List<Finding> apply(List<Finding> findings);
}

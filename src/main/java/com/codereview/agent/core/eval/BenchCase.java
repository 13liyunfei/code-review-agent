package com.codereview.agent.core.eval;

import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.Finding;

import java.util.List;

/**
 * 基准语料的一条 case：真实 diff + 出处 + ground-truth + 回放输入。
 *
 * <p><b>provenance 是硬要求</b>：每条 case 必须能回答"它是从哪个仓库的哪个提交来的"。
 * 语料由 {@code tools/bench/collect_corpus.py} 采集，所有数字（文件数 / 增删行 / patch 哈希）
 * 都是从 git 或 GitHub API 现取的，不许手写。
 *
 * @param id               用例 ID（形如 {@code cra-7410a93} / {@code oss-pgvector-890}）
 * @param repoKey          仓库标识（{@code code-review-agent} 或 {@code owner/repo}）
 * @param provenance       来源类型：{@code git-history} / {@code github-pr}
 * @param shaShort         提交短 SHA（可回源）
 * @param subject          提交/PR 标题
 * @param changedFileCount 改动文件数
 * @param patchSha256      patch 的 SHA-256（语料漂移检测，见 manifest）
 * @param groundTruth      人工标注的真问题
 * @param replayInput      回放输入（冻结的候选发现 + 反馈）
 */
public record BenchCase(
        String id,
        String repoKey,
        String provenance,
        String shaShort,
        String subject,
        int changedFileCount,
        int addedLines,
        int deletedLines,
        String patchSha256,
        List<BenchGroundTruth> groundTruth,
        ReplayInput replayInput) {

    /**
     * 回放输入。
     *
     * <p>★ <b>origin 必须如实写</b>：当前语料的 origin 是
     * {@code derived-from-ground-truth+controlled-distractors}——也就是说这批候选发现
     * **不是真实模型产出**，而是由 ground-truth 加受控干扰项派生出来的。
     * 因此本基准回答的是「**同一批候选，经过去重/仲裁/抑制/否决/聚合之后，剩下的集合对不对**」，
     * 测的是**确定性后处理链**，不是模型质量。等真实模型采集（{@code origin=captured}）
     * 接进来之后，同一套指标与门禁可以直接复用。
     *
     * @param origin   来源标记（{@code none} 表示该 case 是负向样本，无回放输入）
     * @param note     人读说明
     * @param findings 候选发现（已展平，agentType 在每条里）
     * @param feedback 预置的开发者反馈（用于验证误报抑制与 BLOCKER 否决）
     */
    public record ReplayInput(String origin, String note,
                              List<Finding> findings, List<ReviewFeedback> feedback) {

        /** 该 case 是否可参与算分。 */
        public boolean scorable() {
            return findings != null && !findings.isEmpty();
        }
    }
}

package com.codereview.agent.core.memory;

import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 反思服务（Memory 反思层补实）：审查完成后从报告提炼可复用经验入经验库。
 *
 * <p>规则驱动为主（确定性、零 LLM 成本）：把 BLOCKER / MAJOR 级发现的
 * 「规则模式 → 修复建议」沉淀为经验条目，供后续同类问题审查时经
 * {@link ExperienceStore#top} 检索注入。LLM 增强可选（传入 llm 时对摘要再做一次
 * 语义提炼，失败静默跳过）。
 */
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private final ExperienceStore store;
    private final LlmClient llm; // 可空

    public ReflectionService(ExperienceStore store, LlmClient llm) {
        this.store = store;
        this.llm = llm;
    }

    /**
     * 从报告反思提炼经验。
     *
     * @return 本次新增的经验条数
     */
    public int reflectFromReport(String teamId, ReviewReport report) {
        if (report == null || store == null) {
            return 0;
        }
        int added = 0;
        List<Finding> candidates = report.getFindings().stream()
                .filter(f -> f.severity() == Severity.BLOCKER || f.severity() == Severity.MAJOR)
                .toList();
        for (Finding f : candidates) {
            String pattern = f.ruleId() + " " + f.title() + " " + f.file();
            int before = store.size(teamId);
            store.add(teamId, pattern, f.suggestion());
            if (store.size(teamId) > before) {
                added++;
            }
        }
        // 可选 LLM 增强：对高频规则做一次语义总结（失败静默）
        if (llm != null && !candidates.isEmpty()) {
            try {
                String summary = llm.chat("用一句话总结以下代码审查高频问题的共性修复思路：\n"
                        + candidates.stream().map(f -> f.ruleId() + ":" + f.title())
                        .distinct().limit(5).reduce((a, b) -> a + "；" + b).orElse(""));
                if (summary != null && !summary.isBlank()) {
                    store.add(teamId, "高频问题共性总结", summary.trim());
                    added++;
                }
            } catch (Exception e) {
                log.debug("[Reflection] LLM 增强总结失败（忽略）：{}", e.getMessage());
            }
        }
        if (added > 0) {
            log.info("[Reflection] 团队 {} 反思沉淀 {} 条经验", teamId, added);
        }
        return added;
    }
}

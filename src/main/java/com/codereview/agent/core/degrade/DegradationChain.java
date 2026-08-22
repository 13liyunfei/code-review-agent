package com.codereview.agent.core.degrade;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 4 级降级链（见文档“稳定：4 级降级链”）。
 *
 * <p>当高级能力不可用时，依次降级以保证系统整体可用：
 * <ol>
 *   <li><b>Agent</b>：多 Agent + LLM 语义审查；</li>
 *   <li><b>固定编排</b>：跳过大模型，仅执行确定性规则；</li>
 *   <li><b>纯规则</b>：最小规则集兜底；</li>
 *   <li><b>人工</b>：以上均不可用，标记需人工复核。</li>
 * </ol>
 * 实现上按优先级依次尝试各降级层，返回第一个非空结果。
 */
public class DegradationChain {

    private static final Logger log = LoggerFactory.getLogger(DegradationChain.class);

    /** 降级层：名称 + 提供发现的供应者。 */
    public record Level(String name, Supplier<List<Finding>> supplier) {
    }

    private final List<Level> levels;

    public DegradationChain(List<Level> levels) {
        this.levels = levels;
    }

    /**
     * 依次尝试各降级层，返回第一个非空（有效）结果。
     *
     * @param prId  PR 标识（用于人工复核标记）
     * @param repo  仓库名
     * @return 审查发现；若所有层均不可用，返回“需人工复核”占位发现
     */
    public List<Finding> execute(long prId, String repo) {
        for (Level level : levels) {
            try {
                List<Finding> result = level.supplier().get();
                if (result != null && !result.isEmpty()) {
                    log.info("[降级链] PR#{} 由层级【{}】产出 {} 条", prId, level.name(), result.size());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[降级链] 层级【{}】执行异常，尝试下一层：{}", level.name(), e.getMessage());
            }
        }
        // 全部失败 → 人工复核
        log.warn("[降级链] PR#{} 所有层级均不可用，标记为需人工复核", prId);
        return List.of(new Finding(AgentType.COORDINATOR, repo, 0, 0, Severity.INFO,
                "degrade", "HUMAN-REVIEW", "需人工复核",
                "自动化审查链路暂不可用，已转人工复核。",
                "请相关 reviewer 手动检查本次变更。", 1.0, "HUMAN"));
    }
}

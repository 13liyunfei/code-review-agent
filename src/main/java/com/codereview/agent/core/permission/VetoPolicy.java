package com.codereview.agent.core.permission;

import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限收敛策略（对齐 codex {@code intersect_permission_profiles} / {@code retain_constraining_deny_entries}）。
 *
 * <p>多 Agent 审查中，总审查员的宽松结论<b>不得覆盖</b>领域专家的强否决：
 * <ul>
 *   <li>聚合阶段的「误报抑制」与「优先级仲裁」可能把某些发现降级或剔除；</li>
 *   <li>但 <b>{@link Severity#BLOCKER} 级发现是「强否决」</b>——任何 Agent 对安全 / 正确性的阻断级结论
 *       都不可被抑制或覆盖（父不覆盖子），必须保留在最终报告中；</li>
 *   <li>被回收的 BLOCKER 从对应列表移除并回到发现列表，报告内统计随之重算。</li>
 * </ul>
 */
@Component
public class VetoPolicy {

    /** 判定一条发现是否为「不可覆盖的强否决」。当前：阻断级。 */
    public static boolean isVetoable(Finding f) {
        return f != null && f.severity() == Severity.BLOCKER;
    }

    /**
     * 对聚合后的报告应用权限收敛：从「已抑制误报」与「被仲裁覆盖」中回收 BLOCKER 级发现。
     *
     * @param report 聚合后的报告
     * @return 收敛后的报告；若无不合法抑制 / 覆盖，原样返回
     */
    public ReviewReport apply(ReviewReport report) {
        if (report == null) {
            return null;
        }
        List<Finding> rescued = new ArrayList<>();
        List<Finding> newSuppressed = new ArrayList<>();
        for (Finding f : report.getSuppressedFindings()) {
            if (isVetoable(f)) {
                rescued.add(f);
            } else {
                newSuppressed.add(f);
            }
        }
        List<Finding> newOverridden = new ArrayList<>();
        for (Finding f : report.getOverriddenFindings()) {
            if (isVetoable(f)) {
                rescued.add(f);
            } else {
                newOverridden.add(f);
            }
        }
        if (rescued.isEmpty()) {
            return report;
        }

        List<Finding> newFindings = new ArrayList<>(report.getFindings());
        for (Finding f : rescued) {
            if (!containsSame(newFindings, f)) {
                newFindings.add(f);
            }
        }
        return report.withPostProcessing(newFindings, newSuppressed, newOverridden);
    }

    private static boolean containsSame(List<Finding> list, Finding target) {
        return list.stream().anyMatch(f -> f.ruleId().equals(target.ruleId())
                && f.file().equals(target.file())
                && f.lineStart() == target.lineStart()
                && f.agentType() == target.agentType());
    }
}

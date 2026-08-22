package com.codereview.agent.core.agent.impl;

import com.codereview.agent.core.agent.AbstractReviewAgent;
import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 性能审查 Agent。
 *
 * <p>审查职责（见文档）：时间复杂度、数据库查询优化（N+1、全表扫描）、内存泄漏等。
 * 确定性规则（SELECT *、循环内创建对象、方法级锁等）已抽取为注册中心内的
 * {@code PatternSkill}，由本 Agent 实时取用；LLM 负责补充语义级发现。
 */
public class PerformanceAgent extends AbstractReviewAgent implements ReviewAgent {

    public PerformanceAgent(LlmClient llmClient,
                            PromptTemplateLoader promptLoader,
                            SkillRegistry registry,
                            ConfidenceCalibrationService calibration,
                            CodeReviewAiService aiService) {
        super(llmClient, promptLoader, registry, calibration, "performance", aiService);
    }

    @Override
    public AgentType getType() {
        return AgentType.PERFORMANCE;
    }

    @Override
    public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // 技能预扫描（内置 PatternSkill + 团队自定义规则，实时取自注册中心）
        runSkills(diffs, ctx).forEach(sr -> findings.add(toFinding(sr)));

        // LLM 增强（混元语义级补充；无 Key 时为空）
        findings.addAll(llmFindings("performance_review", defaultVars(diffs, ctx, "性能优化专家")));
        return findings;
    }
}

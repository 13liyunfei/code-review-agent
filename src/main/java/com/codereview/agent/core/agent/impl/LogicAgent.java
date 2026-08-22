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
 * 逻辑审查 Agent。
 *
 * <p>审查职责（见文档）：业务正确性——算法逻辑、边界条件、异常处理、单元测试覆盖。
 * 确定性规则（空 catch、printStackTrace、System.out 等）已抽取为注册中心内的
 * {@code PatternSkill}，由本 Agent 实时取用；LLM 负责补充语义级发现。
 */
public class LogicAgent extends AbstractReviewAgent implements ReviewAgent {

    public LogicAgent(LlmClient llmClient,
                      PromptTemplateLoader promptLoader,
                      SkillRegistry registry,
                      ConfidenceCalibrationService calibration,
                      CodeReviewAiService aiService) {
        super(llmClient, promptLoader, registry, calibration, "logic", aiService);
    }

    @Override
    public AgentType getType() {
        return AgentType.LOGIC;
    }

    @Override
    public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // 技能预扫描（内置 PatternSkill + 团队自定义规则，实时取自注册中心）
        runSkills(diffs, ctx).forEach(sr -> findings.add(toFinding(sr)));

        // LLM 增强（混元语义级补充；无 Key 时为空）
        findings.addAll(llmFindings("logic_review", defaultVars(diffs, ctx, "资深后端工程师")));
        return findings;
    }
}

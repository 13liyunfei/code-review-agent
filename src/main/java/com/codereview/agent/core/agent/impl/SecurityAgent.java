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
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全审查 Agent。
 *
 * <p>审查职责（见文档）：SQL 注入、XSS、密钥泄露、越权、依赖漏洞等。
 * 实现要点：
 * <ol>
 *   <li>输入防护：审查前先做 Prompt 注入检测，发现疑似注入直接上报 BLOCKER；</li>
 *   <li>技能预扫描：通过注册中心挂接的技能（硬编码密钥、SQL 注入、团队自定义等）做确定性、高置信检测；</li>
 *   <li>LLM 增强：渲染安全审查提示词并调用大模型补充语义级发现。</li>
 * </ol>
 */
public class SecurityAgent extends AbstractReviewAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(SecurityAgent.class);

    /** Prompt 注入检测器（纵深防御第一层）。 */
    private final InjectionDetector injectionDetector;

    public SecurityAgent(LlmClient llmClient,
                         PromptTemplateLoader promptLoader,
                         SkillRegistry registry,
                         ConfidenceCalibrationService calibration,
                         InjectionDetector injectionDetector,
                         CodeReviewAiService aiService) {
        super(llmClient, promptLoader, registry, calibration, "security", aiService);
        this.injectionDetector = injectionDetector;
    }

    @Override
    public AgentType getType() {
        return AgentType.SECURITY;
    }

    @Override
    public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
        List<Finding> findings = new ArrayList<>();

        // ===== 1. 输入防护：Prompt 注入检测 =====
        String combined = diffs.stream().map(CodeDiff::patch).reduce("", String::concat);
        if (injectionDetector.detect(combined)) {
            log.warn("[SecurityAgent] PR#{} 检测到疑似 Prompt 注入，拒绝执行语义审查", ctx.prId());
            findings.add(new Finding(AgentType.SECURITY, "SYSTEM", 0, 0, Severity.BLOCKER,
                    "security", "SEC-INJECTION-001", "输入内容疑似包含 Prompt 注入攻击",
                    "代码注释 / 提交信息中包含试图覆盖系统指令的模式，可能存在恶意内容。",
                    "请人工复核相关代码与提交信息，确认无恶意注入后再审查。", 1.0, "RULE"));
            return findings;
        }

        // ===== 2. 技能预扫描（确定性，高置信，实时取自注册中心） =====
        runSkills(diffs, ctx).forEach(sr -> findings.add(toFinding(sr)));

        // ===== 3. LLM 增强（混元语义级补充；无 Key 时返回空，不影响规则发现） =====
        findings.addAll(llmFindings("security_review", defaultVars(diffs, ctx, "安全工程师")));

        return findings;
    }
}

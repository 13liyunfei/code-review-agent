package com.codereview.agent.core.agent;

import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.LlmFindingParser;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.llm.aiservice.ReviewFindingDto;
import com.codereview.agent.core.llm.aiservice.ReviewResultDto;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审查 Agent 抽象基类，封装各 Agent 的公共能力。
 *
 * <p>公共能力包括：Skill 预扫描、置信度校准、提示词渲染、LLM 调用。
 * 具体 Agent 仅需实现 {@link #review(List, ReviewContext)} 并复用这些能力。
 */
public abstract class AbstractReviewAgent implements ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractReviewAgent.class);

    /** 大模型客户端（用于 LLM 增强，离线环境为 Mock）。 */
    protected final LlmClient llmClient;
    /** LangChain4j AiServices（结构化输出 + ChatMemory；可能为 null → 回退文本路径）。 */
    protected final CodeReviewAiService aiService;
    /** 提示词模板加载器。 */
    protected final PromptTemplateLoader promptLoader;
    /** 技能注册中心（实时取用已启用的技能）。 */
    protected final SkillRegistry registry;
    /** 置信度校准服务。 */
    protected final ConfidenceCalibrationService calibration;
    /** 本 Agent 的问题分类标识（如 security / performance）。 */
    protected final String category;

    protected AbstractReviewAgent(LlmClient llmClient,
                                 PromptTemplateLoader promptLoader,
                                 SkillRegistry registry,
                                 ConfidenceCalibrationService calibration,
                                 String category,
                                 CodeReviewAiService aiService) {
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.registry = registry;
        this.calibration = calibration;
        this.category = category;
        this.aiService = aiService;
    }

    @Override
    public abstract AgentType getType();

    /**
     * 运行本 Agent 维度下、当前已启用的全部 Skill，返回原始结果（并行流加速）。
     *
     * <p>技能集合由 {@link SkillRegistry} 实时提供，支持运行期启停与自定义规则增删。
     *
     * @param diffs 代码变更
     * @param ctx   上下文
     * @return 技能结果列表
     */
    protected List<SkillResult> runSkills(List<CodeDiff> diffs, ReviewContext ctx) {
        return registry.getEnabledSkillsForCategory(ctx.teamId(), category).parallelStream()
                .flatMap(s -> s.execute(diffs, ctx).stream())
                .toList();
    }

    /**
     * 将技能结果转换为 Finding，并应用历史置信度校准。
     *
     * @param sr 技能检测结果
     * @return 校准后的发现
     */
    protected Finding toFinding(SkillResult sr) {
        double conf = calibration.calibrate(sr.ruleId(), sr.confidence());
        return new Finding(getType(), sr.file(), sr.lineStart(), sr.lineStart(),
                sr.severity(), category, sr.ruleId(), sr.title(),
                sr.description(), sr.suggestion(), conf, "SKILL");
    }

    /**
     * 便捷方法：构造一条规则型发现（默认 Major 级、RULE 来源、高置信）。
     *
     * @param file        文件
     * @param line        行号
     * @param ruleId      规则 ID
     * @param title       标题
     * @param description 描述
     * @param suggestion  建议
     * @return Finding
     */
    protected Finding ruleFinding(String file, int line, String ruleId,
                                 String title, String description, String suggestion) {
        double conf = calibration.calibrate(ruleId, 0.95);
        return new Finding(getType(), file, line, line, Severity.MAJOR,
                category, ruleId, title, description, suggestion, conf, "RULE");
    }

    /**
     * 渲染提示词模板。
     *
     * @param templateName 模板名
     * @param variables    变量
     * @return 渲染后的提示词
     */
    protected String renderPrompt(String templateName, Map<String, Object> variables) {
        return promptLoader.render(templateName, variables);
    }

    /**
     * 调用大模型（用于 LLM 增强环节）。
     *
     * @param prompt 提示词
     * @return 模型输出
     */
    protected String askLlm(String prompt) {
        return llmClient.chat(prompt);
    }

    /**
     * 将代码变更格式化为可读文本，供注入提示词。
     *
     * @param diffs 代码变更列表
     * @return 格式化文本
     */
    protected String formatDiffs(List<CodeDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        for (CodeDiff d : diffs) {
            sb.append("--- ").append(d.fileName()).append(" ---\n").append(d.patch()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 构造注入提示词的通用变量（role/repo/author/changedFiles/diffs）。
     *
     * @param diffs 代码变更
     * @param ctx   上下文
     * @param role  角色名（如“安全工程师”）
     * @return 变量 map
     */
    protected Map<String, Object> defaultVars(List<CodeDiff> diffs, ReviewContext ctx, String role) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("role", role);
        vars.put("repo", ctx.repo());
        vars.put("author", ctx.author());
        vars.put("changedFiles", ctx.changedFiles());
        vars.put("diffs", formatDiffs(diffs));
        vars.put("impactSummary", ctx.impactSummary() == null ? "" : ctx.impactSummary());
        vars.put("prId", ctx.prId());
        return vars;
    }

    /**
     * 渲染提示词 → 调用 LLM → 解析为 Finding 列表。
     *
     * <p>封装“LLM 作为聪明的总结者”环节：规则先出确定性结论，LLM 负责补充语义级发现。
     * 优先走 LangChain4j AiServices 结构化输出（含 ChatMemory 短期记忆）；
     * 调用或解析失败时回退到文本解析（{@link LlmFindingParser}），仍失败则返回空列表，
     * 不影响规则型发现。
     *
     * @param templateName 提示词模板名
     * @param variables    模板变量
     * @return LLM 解析出的发现列表
     */
    protected List<Finding> llmFindings(String templateName, Map<String, Object> variables) {
        String prompt = renderPrompt(templateName, variables);
        String mode = aiService != null ? "AiServices" : "文本";
        long t0 = System.currentTimeMillis();
        log.info("[{}] LLM增强 开始：template={}, 模式={}, prompt={}字符",
                getType(), templateName, mode, prompt.length());

        List<Finding> result;
        if (aiService != null) {
            try {
                String memoryId = memoryIdFor(variables);
                ReviewResultDto dto = aiService.review(memoryId, prompt);
                if (dto != null && dto.findings() != null && !dto.findings().isEmpty()) {
                    result = mapFindings(dto.findings());
                    log.info("[{}] LLM增强 完成(AiServices)：返回 {} 条，耗时 {}ms",
                            getType(), result.size(), System.currentTimeMillis() - t0);
                    return result;
                }
                log.info("[{}] LLM增强 AiServices 返回空，回退文本解析", getType());
            } catch (Exception e) {
                log.warn("[{}] LLM增强 AiServices 失败，回退文本解析：{}（已耗时 {}ms）",
                        getType(), e.getMessage(), System.currentTimeMillis() - t0);
            }
        }
        String response = askLlm(prompt);
        result = LlmFindingParser.parse(response, getType(), category);
        log.info("[{}] LLM增强 完成(文本解析)：返回 {} 条，总耗时 {}ms",
                getType(), result.size(), System.currentTimeMillis() - t0);
        return result;
    }

    /** 短期记忆键：Agent-团队-PR（团队隔离 + 单 PR 上下文）。 */
    private String memoryIdFor(Map<String, Object> vars) {
        return getType().name() + "-" + vars.getOrDefault("prId", "shared");
    }

    /** 将 AiServices 结构化 DTO 映射为 Finding。 */
    private List<Finding> mapFindings(List<ReviewFindingDto> dtos) {
        List<Finding> out = new ArrayList<>();
        for (ReviewFindingDto d : dtos) {
            try {
                Severity severity = parseSeverity(d.severity());
                String file = (d.file() == null || d.file().isBlank()) ? "unknown" : d.file();
                int line = d.line() == null ? 0 : d.line();
                double confidence = d.confidence() == null ? 0.75 : d.confidence();
                out.add(new Finding(getType(), file, line, line, severity, category,
                        d.ruleId() == null || d.ruleId().isBlank() ? "LLM-" + getType().name() : d.ruleId(),
                        d.title() == null || d.title().isBlank() ? "LLM 发现" : d.title(),
                        d.description() == null ? "" : d.description(),
                        d.suggestion() == null ? "" : d.suggestion(),
                        confidence, "LLM"));
            } catch (Exception ex) {
                log.debug("[{}] 跳过无法映射的结构化条目：{}", getType(), ex.getMessage());
            }
        }
        return out;
    }

    private static Severity parseSeverity(String s) {
        if (s == null) {
            return Severity.MAJOR;
        }
        return switch (s.trim().toUpperCase()) {
            case "BLOCKER" -> Severity.BLOCKER;
            case "MAJOR" -> Severity.MAJOR;
            case "MINOR" -> Severity.MINOR;
            case "INFO" -> Severity.INFO;
            default -> Severity.MAJOR;
        };
    }
}

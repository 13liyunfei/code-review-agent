package com.codereview.agent.core.llm.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiService：声明式定义审查 / 修复两类 LLM 交互。
 *
 * <p>相比改造前「拼接提示词 → 文本回复 → 手写解析」：
 * <ul>
 *   <li><b>结构化输出</b>：返回类型为 record，LangChain4j 自动要求模型输出 JSON 并反序列化；</li>
 *   <li><b>短期记忆</b>：{@link MemoryId} + ChatMemoryProvider 按「Agent-团队-PR」维护窗口记忆，
 *       替代原 SHORT_TERM 枚举（本系统三层记忆中的短期层）；</li>
 *   <li><b>失败降级</b>：调用/解析失败时，上层回退到文本路径（Mock 或 LlmFindingParser）。</li>
 * </ul>
 */
public interface CodeReviewAiService {

    /**
     * 语义级代码审查：返回结构化发现列表。
     *
     * @param memoryId 短期记忆键（约定 "AGENT-teamId-prId"）
     * @param prompt   完整提示词（含角色与代码变更，由类路径模板渲染）
     * @return 结构化发现
     */
    @SystemMessage("你是代码审查 AI 助手。请严格遵循用户消息中的角色设定与审查指令，"
            + "仅输出符合给定结构的 JSON 结果，不要输出任何多余说明。")
    ReviewResultDto review(@MemoryId String memoryId, @UserMessage String prompt);

    /**
     * 自动修复：为审查发现生成最小、可直接采纳的修复代码。
     *
     * @param memoryId 短期记忆键
     * @param issue    问题描述（文件 / 行号 / 标题 / 描述 / 建议）
     * @return 修复代码片段
     */
    @SystemMessage("你是资深工程师，为代码审查发现生成最小、可直接采纳的修复代码。仅输出代码，不要解释。")
    FixResultDto fix(@MemoryId String memoryId, @UserMessage String issue);
}

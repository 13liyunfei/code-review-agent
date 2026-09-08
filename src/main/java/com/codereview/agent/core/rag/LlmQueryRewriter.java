package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大模型查询改写器：把「代码 patch 形态」的原始查询改写为「规范术语形态」的检索查询。
 *
 * <p>审查知识检索的痛点：库内规范文档（编码规范 / 安全手册 / 历史 PR 复盘）是自然语言
 * 术语（如「SQL 注入」「预处理语句」「参数绑定」），而查询来自 diff 文本（充满符号、
 * 行号、变量名）。直接把 patch 前 500 字符丢给向量库，语义鸿沟导致召回不佳。
 * 本组件让模型先做一次"术语归一化"，把 diff 提炼为知识库更可能命中的关键词。
 *
 * <p><b>可靠性与降级契约</b>（fail-safe，绝不劣化检索）：
 * <ul>
 *   <li>LLM 调用异常（网关超时 / 熔断 / 网络）→ 捕获并回退 {@link IdentityQueryRewriter}；</li>
 *   <li>模型输出为空白 / 长度越界（&lt;1 或 &gt;200 字符）→ 视为无效，回退原查询；</li>
 *   <li>结果仅取单行（去首尾空白与引号包裹），避免模型附带解释性文本污染查询。</li>
 * </ul>
 *
 * <p>本类只负责"改写"，不含任何业务知识注入；输出作为混合检索查询使用，
 * 不直接拼进提示词，安全边界与原始查询一致。
 */
public class LlmQueryRewriter implements ReviewQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 改写结果长度下限（低于视为模型没认真回答，丢弃）。 */
    private static final int MIN_LEN = 1;
    /** 改写结果长度上限（防止模型把整段原文抄回来撑爆查询）。 */
    private static final int MAX_LEN = 200;

    private final LlmClient llmClient;
    private final ReviewQueryRewriter fallback;
    private final String promptTemplate;

    public LlmQueryRewriter(LlmClient llmClient) {
        this(llmClient, new IdentityQueryRewriter());
    }

    public LlmQueryRewriter(LlmClient llmClient, ReviewQueryRewriter fallback) {
        this.llmClient = llmClient;
        this.fallback = fallback == null ? new IdentityQueryRewriter() : fallback;
        this.promptTemplate = buildPrompt();
    }

    /**
     * 改写入口：失败一律回退，不向上抛（调用方无需感知改写层的存在）。
     */
    @Override
    public String rewrite(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return rawQuery == null ? "" : rawQuery;
        }
        try {
            String prompt = promptTemplate + "\n\n<diff 片段>\n" + rawQuery + "\n</diff 片段>";
            String out = llmClient.chat(prompt);
            String cleaned = sanitize(out);
            if (cleaned == null) {
                log.debug("[RagRewrite] 模型输出无效（空白/越界），回退原查询");
                return fallback.rewrite(rawQuery);
            }
            log.info("[RagRewrite] 查询改写：{} 字符 → {} 字符", rawQuery.length(), cleaned.length());
            return cleaned;
        } catch (Exception e) {
            // fail-safe：改写只是增强项，绝不能因它阻断审查主链路
            log.warn("[RagRewrite] 改写失败，回退原查询: {}", e.getMessage());
            return fallback.rewrite(rawQuery);
        }
    }

    /** 清洗模型输出：去首尾空白 / 引号，只保留单行；非法返回 null（触发回退）。 */
    private String sanitize(String out) {
        if (out == null) {
            return null;
        }
        String t = out.trim();
        // 只保留第一行，去掉模型可能附加的解释（先裁行再剥引号，避免首尾引号跨行误判）
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl).trim();
        }
        // 去可能的引号包裹（模型常爱输出 "..." 或 '...'）
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.length() < MIN_LEN || t.length() > MAX_LEN) {
            return null;
        }
        return t;
    }

    private String buildPrompt() {
        return """
                你是代码审查知识库的检索查询改写器。给定一段代码变更（diff）片段，
                请把它改写为 1~3 个最能命中「编码规范/安全规则文档」的检索关键词短语，
                要求：
                1. 只输出改写后的查询文本，不要解释、不要编号、不要引号；
                2. 聚焦技术主题（如 SQL 注入、日志脱敏、事务边界、异常吞噬、并发安全）；
                3. 保留代码中出现的专有名词与 API 名（如 prepared statement、HttpClient）；
                4. 用中文输出主题词，可夹带英文 API 名；总长不超过 80 字；
                5. 若 diff 与规范无关（如纯文案），输出「与规范无关」四个字即可。
                """;
    }
}

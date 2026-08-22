package com.codereview.agent.core.llm;

/**
 * 离线可用的 Mock 大模型客户端。
 *
 * <p>用于无 API Key 环境下的演示与测试。当未配置 {@code tokenhub.api-key} 时，
 * 由装配工厂自动回退到本实现，保证演示链路闭环可运行；配置 Key 后自动切换为
 * LangChain4j {@code OpenAiChatModel}（TokenHub OpenAI 兼容协议），业务代码无需改动。
 *
 * <p>本实现返回一段稳定的占位文本，{@link LlmFindingParser} 解析后不会产生 finding，
 * 因此演示中各 Agent 仅输出规则型发现。
 */
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(String prompt) {
        // 离线环境不真正调用远端模型，仅返回一段稳定的占位分析，
        // 使上层依赖“LLM 增强”的环节在演示中也能跑通。
        if (prompt == null || prompt.isBlank()) {
            return "{}";
        }
        return "{\n" +
                "                  \"analysis\": \"（Mock LLM）已接收审查上下文，未发现需额外标记的明显问题。\",\n" +
                "                  \"note\": \"请在生产环境接入真实大模型以获得语义级审查能力。\"\n" +
                "                }";
    }
}

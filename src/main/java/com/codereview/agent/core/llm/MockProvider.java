package com.codereview.agent.core.llm;

/**
 * Mock 模型供应商：永远可用，作为网关的兜底（Failover 终点）。
 */
public class MockProvider implements ModelProvider {

    private final MockLlmClient client = new MockLlmClient();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String chat(String prompt) {
        return client.chat(prompt);
    }
}

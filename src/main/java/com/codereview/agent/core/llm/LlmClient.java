package com.codereview.agent.core.llm;

/**
 * 大模型客户端抽象。
 *
 * <p>系统设计原则（见文档）：代码审查是高确定性任务，LLM 的最佳角色是
 * “聪明的总结者”而非“随机的决策者”。因此本接口刻意保持精简，
 * 仅提供基础的对话能力；真实的 Function Calling / 多模型路由可在其实现类中扩展。
 */
public interface LlmClient {

    /**
     * 向大模型发送一段提示词并返回文本回复。
     *
     * @param prompt 完整提示词（含系统指令与用户输入）
     * @return 模型文本输出
     */
    String chat(String prompt);
}

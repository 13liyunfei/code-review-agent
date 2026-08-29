package com.codereview.kit;

/**
 * LLM 会话抽象（kit 唯一的模型边界）。
 *
 * <p>刻意保持与经典 LLM 客户端一致的最小形态：单方法 {@code chat}。
 * 任何项目只需一行适配器即可把自研网关 / OpenAI SDK / 内部 MaaS 接入 kit。
 * 与具体供应商解耦，也便于测试（脚本化 fake）。
 */
public interface ChatModel {

    /**
     * 向大模型发送完整提示词并返回文本回复。
     *
     * @param prompt 完整提示词（含系统指令与用户输入）
     * @return 模型文本输出
     */
    String chat(String prompt);
}

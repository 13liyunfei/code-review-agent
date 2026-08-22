package com.codereview.agent.core.llm;

/**
 * 模型供应商抽象（统一模型网关的路由单元）。
 *
 * <p>各厂商（混元 / OpenAI 兼容 / Azure / Mock）实现本接口，由 {@link ModelGateway}
 * 按可用性与配额统一编排，实现「多厂商 + Quota + Failover」。
 */
public interface ModelProvider {

    /** 供应商名称（用于日志与路由）。 */
    String name();

    /** 当前是否可用（如缺少 API Key 则不可用，网关将跳过）。 */
    boolean available();

    /**
     * 发送提示词并获取回复。
     *
     * @param prompt 提示词
     * @return 模型输出
     * @throws Exception 调用失败（触发网关失败转移）
     */
    String chat(String prompt) throws Exception;
}

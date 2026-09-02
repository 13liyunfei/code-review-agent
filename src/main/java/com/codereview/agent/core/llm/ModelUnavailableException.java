package com.codereview.agent.core.llm;

/**
 * 模型不可用异常：所有供应商（含兜底）均调用失败时抛出。
 *
 * <p><b>为什么必须抛而不是返回空串</b>：调用方无法区分「模型认为没有可返回的结论」与
 * 「模型根本没被调到」。返回空串会让上层解析出 0 条发现，最终产出一份看起来完全通过的
 * 审查报告——静默失败比显式报错危险得多。抛出该异常后，协调器将该 Agent 标记为降级，
 * 报告中如实标注「本次未产出可信结论」。
 *
 * <p>该异常继承 {@link RuntimeException}，因此不会破坏 {@link LlmClient#chat(String)}
 * 的既有签名；上层已有的 try-catch 仍可兜住，但**必须**把降级状态写进结果，而不是吞掉。
 */
public class ModelUnavailableException extends RuntimeException {

    private final int attempts;
    private final int providerCount;

    /**
     * @param message       人类可读原因
     * @param providerCount 参与尝试的供应商总数
     * @param attempts      实际发起过的调用次数
     */
    public ModelUnavailableException(String message, int providerCount, int attempts) {
        super(message);
        this.providerCount = providerCount;
        this.attempts = attempts;
    }

    /**
     * @param message       人类可读原因
     * @param providerCount 参与尝试的供应商总数
     * @param attempts      实际发起过的调用次数
     * @param cause         最后一次失败的异常
     */
    public ModelUnavailableException(String message, int providerCount, int attempts, Throwable cause) {
        super(message, cause);
        this.providerCount = providerCount;
        this.attempts = attempts;
    }

    /** 实际发起过的调用次数（受 available / 配额过滤影响，可能小于供应商总数）。 */
    public int attempts() {
        return attempts;
    }

    /** 配置的供应商总数。 */
    public int providerCount() {
        return providerCount;
    }
}

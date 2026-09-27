package com.codereview.agent.core.gate;

/**
 * 单个文件过闸的结论：**通过，或因为某一个明确的原因被拦下**。
 *
 * <p>本记录刻意做成「要么通过、要么带原因」的二元形态，没有第三种状态——
 * 闸门最危险的失效模式不是拦错，而是<b>静默放行</b>：一个本该被拦的密钥文件
 * 因为判据写漏而悄悄进了 prompt，事后没有人能在日志里看出「它为什么被放过」。
 * 因此构造器强制 {@code admitted} 与 {@code reason} 自洽：
 * {@code admitted=true} 只能配 {@link GateReason#ADMITTED}。
 *
 * @param path     文件相对路径（原样保留，便于回显给开发者）
 * @param admitted 是否进入送审清单
 * @param reason   判定原因（非 null）
 */
public record GateDecision(String path, boolean admitted, GateReason reason) {

    /**
     * 紧凑构造器：拒绝空路径与不自洽的组合。
     *
     * @throws IllegalArgumentException 路径为空、原因为 null、或 admitted 与 reason 不自洽
     */
    public GateDecision {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为 null：不确定原因时必须显式给出，不允许静默通过");
        }
        if (admitted != (reason == GateReason.ADMITTED)) {
            throw new IllegalArgumentException(
                    "admitted 与 reason 不自洽：" + admitted + " / " + reason.code());
        }
    }

    /** 构造「通过」结论。 */
    public static GateDecision admit(String path) {
        return new GateDecision(path, true, GateReason.ADMITTED);
    }

    /** 构造「被拦」结论。 */
    public static GateDecision block(String path, GateReason reason) {
        return new GateDecision(path, false, reason);
    }

    /** 人类可读的一行描述（日志与审查评论用）。 */
    public String describe() {
        return admitted ? path + " -> admitted" : path + " -> " + reason.code();
    }
}

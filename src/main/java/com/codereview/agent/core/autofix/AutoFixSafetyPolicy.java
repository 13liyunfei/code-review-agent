package com.codereview.agent.core.autofix;

/**
 * 自动修复安全策略（fail-closed）。
 *
 * <p>对齐 deepseek-harness 沙箱「不可用即拒绝运行」与 codex {@code guardian-v2}
 * 的「超时/调用失败一律拒绝」。核心不变量：<b>任何代码变更都不得在无隔离保障下静默发生</b>。
 *
 * <p>裁定规则：
 * <ul>
 *   <li>{@code SUGGEST}：仅生成建议，不产生代码变更 → 永远安全（allowed）；</li>
 *   <li>{@code APPLY}：必须 {@code sandboxAvailable == true} → 允许（在隔离环境内应用）；
 *       沙箱不可用时<b>坚决拒绝</b>（denied），绝不退化为「无沙箱直接应用」。</li>
 * </ul>
 */
public final class AutoFixSafetyPolicy {

    private AutoFixSafetyPolicy() {
    }

    /** 安全裁定结论。 */
    public record Verdict(boolean allowed, String reason) {}

    /**
     * 评估在给定模式下、给定沙箱可用性时，是否允许应用修复。
     *
     * @param mode             运行模式
     * @param sandboxAvailable 沙箱（隔离工作区、禁用外网）是否可用
     * @return 裁定结论（allowed + 原因）
     */
    public static Verdict evaluate(AutoFixMode mode, boolean sandboxAvailable) {
        return switch (mode) {
            case SUGGEST -> new Verdict(true,
                    "SUGGEST 模式仅生成建议，不产生代码变更（默认安全）");
            case APPLY -> sandboxAvailable
                    ? new Verdict(true, "APPLY 模式且沙箱可用，允许在隔离环境内应用修复")
                    : new Verdict(false,
                    "fail-closed：APPLY 模式但沙箱不可用，拒绝应用，避免未隔离的代码变更");
        };
    }

    /**
     * 便捷判断：是否允许应用修复。
     *
     * @param mode             运行模式
     * @param sandboxAvailable 沙箱是否可用
     * @return true=允许
     */
    public static boolean isApplyAllowed(AutoFixMode mode, boolean sandboxAvailable) {
        return evaluate(mode, sandboxAvailable).allowed();
    }
}

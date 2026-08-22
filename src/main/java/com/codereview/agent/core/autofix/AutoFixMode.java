package com.codereview.agent.core.autofix;

/**
 * 自动修复运行模式。
 *
 * <p>对齐 deepseek-harness 的「失败即关闭（fail-closed）」与 codex 的
 * {@code guardian-v2} 异步风险网关：任何对代码的<b>实际变更</b>都必须显式授权，
 * 且只能在受控沙箱内进行；默认 {@code SUGGEST}（仅生成建议，绝不改动代码）。
 */
public enum AutoFixMode {
    /** 仅生成可一键采纳的修复建议（suggestion 块），不产生任何代码变更（默认、最安全）。 */
    SUGGEST,
    /** 在隔离沙箱内应用修复。要求沙箱可用，否则按 fail-closed 拒绝（见 {@link AutoFixSafetyPolicy}）。 */
    APPLY
}

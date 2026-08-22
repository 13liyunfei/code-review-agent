package com.codereview.agent.core.tools;

/**
 * 工具暴露等级（对齐 codex {@code ToolExposures} DIRECT/DEFERRED/CODE_MODE）。
 *
 * <p>不同工具对审查管线的风险 / 成本不同，暴露策略分级：
 * <ul>
 *   <li>{@link #DIRECT}：轻量、默认可用（如读取文件、查规则）—— 主审可直接调用；</li>
 *   <li>{@link #DEFERRED}：重工具（全量编译、跑测试、AutoFix 实际写代码）—— 默认<b>不暴露</b>，
 *       仅当配置允许或审查强度为 STRICT 时才放行；</li>
 *   <li>{@link #CODE_MODE}：代码模式专用工具（仅在显式进入代码模式时暴露）。</li>
 * </ul>
 */
public enum ToolExposure {
    /** 轻量工具，默认可用。 */
    DIRECT,
    /** 重工具，默认拒绝（fail-closed），需显式授权。 */
    DEFERRED,
    /** 代码模式专用工具，仅显式进入代码模式时暴露。 */
    CODE_MODE
}

package com.codereview.agent.core.tools;

import com.codereview.agent.core.profile.ReviewProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 工具调用门控（fail-closed，对齐 dsh 沙箱「不可用即拒绝」）。
 *
 * <p>核心语义：
 * <ul>
 *   <li>{@link ToolExposure#DIRECT}：默认放行；</li>
 *   <li>{@link ToolExposure#DEFERRED}：默认<b>拒绝</b>，仅当 {@code review.tools.deferred-enabled=true}
 *       或当前审查强度为 {@link ReviewProfile#STRICT} 时放行（重工具只在需要时显式暴露）；</li>
 *   <li>{@link ToolExposure#CODE_MODE}：仅当 {@code review.tools.code-mode-enabled=true} 时放行。</li>
 * </ul>
 *
 * <p>每次裁定均记录调用统计（工具名 → 放行 / 拒绝计数），便于审计「哪些重工具被尝试调用」。
 */
@Component
public class ToolGate {

    private static final Logger log = LoggerFactory.getLogger(ToolGate.class);

    /** 是否放行 DEFERRED 重工具（默认 false，fail-closed）。 */
    private final boolean deferredEnabled;
    /** 是否放行 CODE_MODE 工具（默认 false）。 */
    private final boolean codeModeEnabled;

    /** 调用统计：tool -> [allowed, denied]。 */
    private final Map<String, long[]> stats = new ConcurrentHashMap<>();

    /**
     * Spring 装配构造（显式 {@code @Autowired}，避免与测试便捷构造混淆）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ToolGate(@Value("${review.tools.deferred-enabled:false}") boolean deferredEnabled,
                    @Value("${review.tools.code-mode-enabled:false}") boolean codeModeEnabled) {
        this.deferredEnabled = deferredEnabled;
        this.codeModeEnabled = codeModeEnabled;
    }

    /**
     * 测试便捷构造（不参与 Spring 装配）。
     */
    public ToolGate(boolean deferredEnabled) {
        this(deferredEnabled, false);
    }

    /**
     * 裁定工具在当前暴露等级下是否可调用。
     *
     * @param tool     工具名（如 {@code autofix.apply}、{@code build.verify}）
     * @param exposure 暴露等级
     * @param profile  当前审查强度（影响 DEFERRED 放行）
     * @return true=允许调用
     */
    public boolean allows(String tool, ToolExposure exposure, ReviewProfile profile) {
        boolean allowed = switch (exposure) {
            case DIRECT -> true;
            case DEFERRED -> deferredEnabled || profile == ReviewProfile.STRICT;
            case CODE_MODE -> codeModeEnabled;
        };
        recordStat(tool, allowed);
        if (!allowed) {
            log.info("[ToolGate] fail-closed：拒绝工具 {}（exposure={}, deferredEnabled={}, profile={}）",
                    tool, exposure, deferredEnabled, profile);
        }
        return allowed;
    }

    /** 便捷：以默认 Profile 裁定（用于无 Profile 上下文的调用方，DEFERRED 一律拒绝）。 */
    public boolean allows(String tool, ToolExposure exposure) {
        return allows(tool, exposure, ReviewProfile.ADVISORY);
    }

    /** 读取某工具的调用统计 [放行数, 拒绝数]（无记录返回 [0,0]）。 */
    public long[] stats(String tool) {
        return stats.getOrDefault(tool, new long[]{0L, 0L});
    }

    private void recordStat(String tool, boolean allowed) {
        long[] c = stats.computeIfAbsent(tool, k -> new long[]{0L, 0L});
        if (allowed) {
            c[0]++;
        } else {
            c[1]++;
        }
    }
}

package com.codereview.agent.core.gate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 送审闸门运行态端点：把「被拦掉了多少、因为什么」暴露给监控。
 *
 * <p>为什么这个端点必须存在：闸门是本项目唯一会<b>减少</b>送审内容的组件。
 * 一个会减少内容的组件如果不可观测，出问题时只能靠人肉翻日志猜；有了按原因聚合的计数，
 * 「某天开始 java 文件突然少了 30%」这类问题会直接指向原因，而不是变成悬案。
 *
 * <p>鉴权与其余 {@code /api/admin/**} 一致（由 {@code review.api.auth-token} 过滤）。
 */
@RestController
@RequestMapping("/api/admin/gate")
public class GateAdminController {

    private final PathGate gate;

    public GateAdminController(PathGate gate) {
        this.gate = gate;
    }

    /**
     * 闸门统计：总开关状态、按原因的累计拦截数、生效的规则条数。
     *
     * @return 统计快照
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", gate.isEnabled() ? "UP" : "DISABLED");
        out.put("blockedByReason", gate.countersSnapshot());
        out.put("binaryExtensions", PathGate.BINARY_EXT.size());
        out.put("secretPatterns", PathGate.SECRET_PATTERNS.size());
        out.put("noiseSegments", PathGate.NOISE_SEGMENTS.size());
        PathGateProperties p = gate.properties();
        out.put("userExclude", p.getExclude().size());
        out.put("userInclude", p.getInclude().size());
        out.put("userAllow", p.getAllow().size());
        out.put("allowListEnabled", p.isAllowListEnabled());
        out.put("maxDiffChars", p.getMaxDiffChars());
        return out;
    }
}

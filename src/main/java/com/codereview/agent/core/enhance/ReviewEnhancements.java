package com.codereview.agent.core.enhance;

import com.codereview.agent.core.mailbox.TeamMailbox;
import com.codereview.agent.core.permission.VetoPolicy;
import com.codereview.agent.core.resume.FileResumeStore;
import com.codereview.agent.core.tools.ToolGate;
import org.springframework.stereotype.Component;

/**
 * Coordinator 可选增强能力的聚合入口（对齐「架构对标」P0-③ / P1-⑥ / P1-⑦ / P2-⑧ 落地）。
 *
 * <p>这些能力<b>均可空</b>：为 null 时 Coordinator 行为与旧版完全一致（零侵入）。
 * Spring 装配时四个组件均为 {@code @Component}，自动注入；测试可显式传入部分组件。
 *
 * @param resumeStore 断点续跑存储（P0-③，可空）
 * @param vetoPolicy  权限收敛（BLOCKER 免于抑制/覆盖，P1-⑥，可空）
 * @param toolGate    工具分级门控（DEFERRED 默认拒绝，P1-⑦，可空）
 * @param mailbox     持久化信箱（多 Agent 委派基础设施，P2-⑧，可空）
 */
@Component
public record ReviewEnhancements(
        FileResumeStore resumeStore,
        VetoPolicy vetoPolicy,
        ToolGate toolGate,
        TeamMailbox mailbox) {

    /** 空增强（全部能力关闭，Coordinator 行为与旧版一致）。 */
    public static ReviewEnhancements none() {
        return new ReviewEnhancements(null, null, null, null);
    }
}

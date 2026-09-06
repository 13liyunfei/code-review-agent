package com.codereview.agent.core.resume;

import com.codereview.agent.core.model.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点残留清理任务：配置绑定 + 清理委托。
 *
 * <p>项目全部为纯单元测试（无 {@code @SpringBootTest}），{@code application.yml} 里的
 * {@code review.resume.ttl} 没有任何测试兜底——格式写错要到启动才炸，所以这里锁住绑定行为。
 */
class ResumeJanitorTest {

    /**
     * Spring 的 Duration 绑定有个坑：<b>不带单位的纯数字按毫秒算</b>。
     * 若有人把 {@code ttl} 写成 {@code 24} 以为是 24 小时，实际是 24 毫秒——
     * 每次清理都会把所有断点（包括正在跑的）一扫而空，且现象是「续跑莫名其妙不生效」，极难排查。
     */
    @Test
    void ttlFormatMustCarryUnit() {
        ApplicationConversionService cs = new ApplicationConversionService();
        assertEquals(Duration.ofHours(24), cs.convert("24h", Duration.class), "ttl=24h 必须解析为 24 小时");
        assertEquals(Duration.ofMillis(24), cs.convert("24", Duration.class), "不带单位按毫秒：这就是要防的坑");
    }

    @Test
    void purgeRemovesStaleCheckpoints() {
        InMemoryResumeStore store = new InMemoryResumeStore();
        store.save(new ResumeState("stale", 1, "r", "teamA",
                Set.of(AgentType.SECURITY), List.of(),
                System.currentTimeMillis() - Duration.ofDays(3).toMillis()));

        new ResumeJanitor(store, Duration.ofHours(24)).purge();

        assertFalse(store.load("stale", "teamA").isPresent(), "定时任务应清理超期断点");
    }

    @Test
    void purgeKeepsFreshCheckpoints() {
        InMemoryResumeStore store = new InMemoryResumeStore();
        store.save(new ResumeState("fresh", 1, "r", "teamA",
                Set.of(AgentType.SECURITY), List.of(), System.currentTimeMillis()));

        new ResumeJanitor(store, Duration.ofHours(24)).purge();

        assertTrue(store.load("fresh", "teamA").isPresent(), "未超期断点必须保留");
    }
}

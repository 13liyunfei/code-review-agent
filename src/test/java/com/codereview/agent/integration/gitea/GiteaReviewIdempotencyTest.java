package com.codereview.agent.integration.gitea;

import com.codereview.agent.core.autofix.AutoFixEngine;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.workflow.ReviewWorkflowEngine;
import com.codereview.agent.tenant.TeamResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gitea webhook 幂等判重验证。
 *
 * <p>背景：Gitea 的 webhook 对同一事件可能重复投递（网络重试 / 手动重推），
 * 若服务层不做判重，同一 PR + 同一 head SHA 会被重复完整审查、重复发评论。
 * 修复 = {@link GiteaReviewService} 注入 {@code ReviewHistoryStore}，审查前按
 * 「仓库 + PR 编号 + head SHA」派生键（与 Coordinator 断点键同源，
 * {@link PullRequest#resumeKey}）对比最近历史 runId 命中即跳过。
 *
 * <p>验证口径：
 * <ol>
 *   <li>同 head SHA 重复投递且已有对应历史 → 跳过（Coordinator / Gitea API 零调用）；</li>
 *   <li>换了新 head SHA → 判重不命中 → 正常重审（必须重新审查而非沿用旧结果）；</li>
 *   <li>head SHA 为空 → 判重关闭（兼容无 SHA 入口），正常审查；</li>
 *   <li>幂等键稳定且文件系统安全（repo 的 "/" 转 "_"）。</li>
 * </ol>
 */
class GiteaReviewIdempotencyTest {

    private static final String OWNER = "demo";
    private static final String REPO = "backend";
    private static final String TEAM = "team-t";
    private static final String REPO_COORD = OWNER + "/" + REPO;

    /** 无网络 Gitea 客户端桩：记录各 API 调用次数（真实对象会打 HTTP，测试不可用）。 */
    private static class StubGiteaClient extends GiteaApiClient {
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger commentCalls = new AtomicInteger();

        StubGiteaClient() {
            super("http://127.0.0.1:1", "test-token");
        }

        @Override
        public PrChanges fetchPrChanges(String owner, String repo, long prNum) {
            fetchCalls.incrementAndGet();
            CodeDiff cd = new CodeDiff("App.java",
                    "diff --git a/App.java b/App.java\n+public void m() {}\n", "java", 1, 0);
            return new PrChanges("feat: change", "@bob", "feature/x", "main", List.of(cd));
        }

        @Override
        public boolean postPrComment(String owner, String repo, long prNum, String body) {
            commentCalls.incrementAndGet();
            return true;
        }

        @Override
        public int postReviewComments(String owner, String repo, long prNum, String sha,
                                      List<ReviewCommentItem> items) {
            return items == null ? 0 : items.size();
        }

        @Override
        public long createIssue(String owner, String repo, String title, String body) {
            return 0;
        }

        @Override
        public boolean createCommitStatus(String owner, String repo, String sha, String state,
                                          String context, String description) {
            return true;
        }
    }

    /** 计数 Coordinator 桩：跳过真实多 Agent 编排。 */
    private static class CountingCoordinator implements Coordinator {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ReviewReport review(PullRequest pr) {
            calls.incrementAndGet();
            return new ReviewReport(pr.id(), pr.repo(), List.of(), Map.of());
        }
    }

    /** 惰性工作流桩：handle 返回空串，避免真实对象在无 BLOCKER 时回调 createCommitStatus。 */
    private static class InertWorkflow extends ReviewWorkflowEngine {
        InertWorkflow(GiteaApiClient client) {
            super(client);
        }

        @Override
        public String handle(ReviewReport report, String owner, String repo, long prNum, String headSha) {
            return "";
        }
    }

    private static GiteaReviewService service(StubGiteaClient client, CountingCoordinator coordinator,
                                              InMemoryReviewHistoryStore historyStore) {
        return new GiteaReviewService(client, coordinator,
                new AutoFixEngine(null, null), new InertWorkflow(client),
                new TeamResolver(Map.of(), TEAM), null, null, historyStore);
    }

    private static String historyKey(long prNum) {
        return REPO_COORD + "#" + prNum;
    }

    // ---------- 幂等键本身 ----------

    @Test
    void resumeKeyIsStableAndFilesystemSafe() {
        // repo 含 "/" 必须替换为 "_"（键会被用作断点/轨迹文件名，裸 "/" 会写出非法路径）
        assertEquals("demo_backend#7@abc123", PullRequest.resumeKey(REPO_COORD, 7, "abc123"),
                "同仓库同 PR 同 headSha 应派生稳定且文件系统安全的键");
        // 无 headSha → 回落到 repo#id（旧调用方兼容）
        assertEquals("demo_backend#7", PullRequest.resumeKey(REPO_COORD, 7, null));
        assertEquals("demo_backend#7", PullRequest.resumeKey(REPO_COORD, 7, "  "));
        // 反斜杠同样替换（防御 Windows 风格路径误用）
        assertEquals("demo_backend#7@x", PullRequest.resumeKey("demo\\backend", 7, "x"));
    }

    // ---------- 判重：同 headSha 重复投递 ----------

    @Test
    void duplicateWebhookWithSameHeadShaIsSkipped() {
        StubGiteaClient client = new StubGiteaClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        InMemoryReviewHistoryStore historyStore = new InMemoryReviewHistoryStore();
        GiteaReviewService service = service(client, coordinator, historyStore);
        long prNum = 7L;
        String headSha = "sha-111";

        // 预置：该 PR + headSha 已完成过一轮审查（历史 runId 与幂等键一致）
        historyStore.save(TEAM, new ReviewHistoryEntry(prNum, REPO_COORD,
                PullRequest.resumeKey(REPO_COORD, prNum, headSha), System.currentTimeMillis(), List.of()));

        service.reviewPullRequest(OWNER, REPO, prNum, headSha);

        // 幂等命中：既不拉取、不审查、也不发任何评论（连跳过说明都不该发——是静默跳过）
        assertEquals(0, coordinator.calls.get(), "同 headSha 重复投递应跳过 Coordinator");
        assertEquals(0, client.fetchCalls.get(), "同 headSha 重复投递不应再拉取 PR 变更");
        assertEquals(0, client.commentCalls.get(), "幂等跳过不应产生任何评论");
    }

    // ---------- 判重：换 headSha 必须重审 ----------

    @Test
    void newHeadShaTriggersFreshReview() {
        StubGiteaClient client = new StubGiteaClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        InMemoryReviewHistoryStore historyStore = new InMemoryReviewHistoryStore();
        GiteaReviewService service = service(client, coordinator, historyStore);
        long prNum = 8L;

        // 预置：旧 commit 的审查历史（headSha=sha-old）
        historyStore.save(TEAM, new ReviewHistoryEntry(prNum, REPO_COORD,
                PullRequest.resumeKey(REPO_COORD, prNum, "sha-old"), System.currentTimeMillis(), List.of()));

        // 新 commit 投递：键不同 → 判重不命中 → 必须重审（沿用旧结果会把新代码的缺陷漏掉）
        service.reviewPullRequest(OWNER, REPO, prNum, "sha-new");

        assertEquals(1, coordinator.calls.get(), "新 headSha 应触发一次全新审查");
        assertEquals(1, client.fetchCalls.get(), "新 headSha 应重新拉取 PR 变更");
        assertTrue(client.commentCalls.get() >= 1, "审查结果应回写评论");
    }

    // ---------- 判重：无 headSha（兼容入口）或未装 historyStore ----------

    @Test
    void blankHeadShaDisablesGuardButStillReviews() {
        StubGiteaClient client = new StubGiteaClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        InMemoryReviewHistoryStore historyStore = new InMemoryReviewHistoryStore();
        GiteaReviewService service = service(client, coordinator, historyStore);

        service.reviewPullRequest(OWNER, REPO, 9L, "");

        assertEquals(1, coordinator.calls.get(), "headSha 为空时判重关闭，应正常审查（兼容旧入口）");
    }

    @Test
    void nullHistoryStoreDisablesGuard() {
        StubGiteaClient client = new StubGiteaClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        // 不注入 historyStore（纯内存/单测场景）
        GiteaReviewService service = new GiteaReviewService(client, coordinator,
                new AutoFixEngine(null, null), new InertWorkflow(client),
                new TeamResolver(Map.of(), TEAM));

        service.reviewPullRequest(OWNER, REPO, 10L, "sha-1");

        assertEquals(1, coordinator.calls.get(), "historyStore 为 null 时幂等判重应禁用，不阻塞审查");
        assertTrue(client.commentCalls.get() >= 1, "审查结果应正常回写");
    }
}

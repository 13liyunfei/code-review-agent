package com.codereview.agent.integration.gitlab;

import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.tenant.TeamResolver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GitLab MR webhook 幂等判重验证（与 Gitea 同构）。
 *
 * <p>背景：GitLab webhook 同样可能重复投递。与 Gitea 不同，GitLab 服务签名不贯穿
 * head SHA，所以判重放在 {@code fetchMrChanges} 之后——用响应自带的头提交 SHA
 * 派生幂等键（与 Coordinator 断点键同源），命中已完成历史即跳过重复审查。
 */
class GitLabReviewIdempotencyTest {

    private static final long PROJECT_ID = 42L;
    private static final String PROJECT_PATH = "group/demo-service";
    private static final String TEAM = "team-t";

    /** 无网络 GitLab 客户端桩。 */
    private static class StubGitLabClient extends GitLabApiClient {
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger noteCalls = new AtomicInteger();

        StubGitLabClient() {
            super("http://127.0.0.1:1", "test-token", Duration.ofSeconds(1));
        }

        @Override
        public MrChanges fetchMrChanges(long projectId, long mrIid) {
            fetchCalls.incrementAndGet();
            CodeDiff cd = new CodeDiff("App.java",
                    "diff --git a/App.java b/App.java\n+public void m() {}\n", "java", 1, 0);
            return new MrChanges("feat: change", "Bob", "sha-111",
                    "feature/x", "main", List.of(cd));
        }

        @Override
        public boolean postMrNote(long projectId, long mrIid, String body) {
            noteCalls.incrementAndGet();
            return true;
        }
    }

    private static class CountingCoordinator implements Coordinator {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ReviewReport review(PullRequest pr) {
            calls.incrementAndGet();
            return new ReviewReport(pr.id(), pr.repo(), List.of(), Map.of());
        }
    }

    private static GitLabReviewService service(StubGitLabClient client, CountingCoordinator coordinator,
                                               InMemoryReviewHistoryStore historyStore) {
        return new GitLabReviewService(client, coordinator,
                new TeamResolver(Map.of(), TEAM), historyStore);
    }

    @Test
    void duplicateWebhookWithSameHeadShaIsSkipped() {
        StubGitLabClient client = new StubGitLabClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        InMemoryReviewHistoryStore historyStore = new InMemoryReviewHistoryStore();
        GitLabReviewService service = service(client, coordinator, historyStore);
        long mrIid = 3L;

        // 预置：该 MR + headSha 已完成过一轮审查（历史 runId = sha-111 派生键）
        historyStore.save(TEAM, new ReviewHistoryEntry(mrIid, PROJECT_PATH,
                PullRequest.resumeKey(PROJECT_PATH, mrIid, "sha-111"), System.currentTimeMillis(), List.of()));

        service.reviewMergeRequest(PROJECT_ID, PROJECT_PATH, mrIid);

        // 幂等命中：拉取后即返回，Coordinator 零调用、不发任何评论
        assertEquals(1, client.fetchCalls.get(), "GitLab 判重须在拉取后执行（sha 来自响应），故允许 1 次轻量 fetch");
        assertEquals(0, coordinator.calls.get(), "同 headSha 重复投递应跳过 Coordinator");
        assertEquals(0, client.noteCalls.get(), "幂等跳过不应产生任何评论");
    }

    @Test
    void newHeadShaTriggersFreshReview() {
        StubGitLabClient client = new StubGitLabClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        InMemoryReviewHistoryStore historyStore = new InMemoryReviewHistoryStore();
        GitLabReviewService service = service(client, coordinator, historyStore);
        long mrIid = 4L;

        // 旧 commit 历史
        historyStore.save(TEAM, new ReviewHistoryEntry(mrIid, PROJECT_PATH,
                PullRequest.resumeKey(PROJECT_PATH, mrIid, "sha-old"), System.currentTimeMillis(), List.of()));

        service.reviewMergeRequest(PROJECT_ID, PROJECT_PATH, mrIid);

        // 响应头 SHA（sha-111）与历史（sha-old）不同 → 判重不命中 → 正常审查回写
        assertEquals(1, coordinator.calls.get(), "新 headSha 应触发一次全新审查");
        assertEquals(1, client.noteCalls.get(), "审查结果应回写 MR 评论");
    }

    @Test
    void nullHistoryStoreDisablesGuard() {
        StubGitLabClient client = new StubGitLabClient();
        CountingCoordinator coordinator = new CountingCoordinator();
        GitLabReviewService service = new GitLabReviewService(client, coordinator,
                new TeamResolver(Map.of(), TEAM));

        service.reviewMergeRequest(PROJECT_ID, PROJECT_PATH, 5L);

        assertEquals(1, coordinator.calls.get(), "historyStore 为 null 时幂等判重应禁用，不阻塞审查");
        assertEquals(1, client.noteCalls.get(), "审查结果应正常回写");
    }
}

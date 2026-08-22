package com.codereview.agent.core.scheduler;

import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.integration.gitea.GiteaApiClient;
import com.codereview.agent.tenant.TeamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时扫描服务（主动式技术债务巡检）。
 *
 * <p>区别于 PR 触发的被动审查：本服务按 cron 主动拉取各仓库目标分支的最新提交 diff，
 * 复用同一套多 Agent 审查管线，产出「技术债务」报告并落盘历史（质量趋势），
 * 同时以 Issue 形式归档，便于持续跟踪。契合「销售易」诉求：每晚主动巡检 main 分支。
 * 各仓库按 owner/repo 解析团队，实现租户隔离。
 */
public class ScheduledScanService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledScanService.class);

    private final GiteaApiClient giteaClient;
    private final Coordinator coordinator;
    private final ReviewHistoryStore historyStore;
    private final List<RepoTarget> repos;
    private final boolean enabled;
    private final TeamResolver teamResolver;

    /** 待扫描仓库目标。 */
    public record RepoTarget(String owner, String repo, String branch) {
    }

    public ScheduledScanService(GiteaApiClient giteaClient, Coordinator coordinator,
                                ReviewHistoryStore historyStore,
                                List<RepoTarget> repos, boolean enabled,
                                TeamResolver teamResolver) {
        this.giteaClient = giteaClient;
        this.coordinator = coordinator;
        this.historyStore = historyStore;
        this.repos = repos;
        this.enabled = enabled;
        this.teamResolver = teamResolver;
    }

    /**
     * 每晚定时巡检（默认 02:00）。
     */
    @Scheduled(cron = "${scan.cron:0 0 2 * * *}")
    public void scanAll() {
        if (!enabled) {
            log.debug("[定时扫描] 未启用（scan.enabled=false），跳过");
            return;
        }
        log.info("[定时扫描] 开始巡检，目标仓库数={}", repos.size());
        for (RepoTarget r : repos) {
            try {
                scanOne(r);
            } catch (Exception e) {
                log.warn("[定时扫描] 仓库 {}/{} 巡检失败：{}", r.owner(), r.repo(), e.getMessage());
            }
        }
    }

    private void scanOne(RepoTarget r) {
        String rawDiff = giteaClient.fetchLatestCommitDiff(r.owner(), r.repo(), r.branch());
        List<CodeDiff> diffs = parseDiff(rawDiff);
        if (diffs.isEmpty()) {
            log.info("[定时扫描] {}/{} 无变更，跳过", r.owner(), r.repo());
            return;
        }
        String teamId = teamResolver.resolve(r.owner(), r.repo());
        PullRequest pr = new PullRequest(0L, r.owner() + "/" + r.repo(),
                "定时扫描@" + r.branch(), "scanner", r.branch(), teamId, diffs);
        ReviewReport report = coordinator.review(pr);

        String summary = buildSummary(r, report);
        long issue = giteaClient.createIssue(r.owner(), r.repo(),
                "📊 技术债务定时巡检 · " + r.branch(), summary);
        log.info("[定时扫描] {}/{} 完成：发现 {} 条，已归档 Issue #{}",
                r.owner(), r.repo(), report.getFindings().size(), issue);
    }

    private String buildSummary(RepoTarget r, ReviewReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("自动定时巡检（分支 `").append(r.branch()).append("`）共发现 **")
                .append(report.getFindings().size()).append("** 个问题：\n\n");
        for (Finding f : report.getFindings()) {
            sb.append("- [").append(f.severity()).append("] `").append(f.file()).append("` L")
                    .append(f.lineStart()).append(" · ").append(f.title()).append("\n");
        }
        sb.append("\n> 本 Issue 由定时扫描自动生成，作为技术债务跟踪基线。\n");
        return sb.toString();
    }

    /** 将完整 unified diff 按文件切分为 CodeDiff 列表（轻量复用 GiteaApiClient 的解析思路）。 */
    private List<CodeDiff> parseDiff(String rawDiff) {
        List<CodeDiff> diffs = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return diffs;
        }
        for (String section : rawDiff.split("(?m)^diff --git ")) {
            if (section.isBlank()) {
                continue;
            }
            String fileDiff = "diff --git " + section;
            String fileName = null;
            int added = 0;
            boolean hasHunk = false;
            for (String line : fileDiff.split("\n")) {
                if (line.startsWith("+++ b/")) {
                    fileName = line.substring(6).trim();
                } else if (fileName == null && line.startsWith("--- a/")) {
                    fileName = line.substring(6).trim();
                } else if (line.startsWith("@@")) {
                    hasHunk = true;
                } else if (hasHunk && line.startsWith("+") && !line.startsWith("+++")) {
                    added++;
                }
            }
            if (fileName != null && hasHunk) {
                diffs.add(new CodeDiff(fileName, fileDiff, CodeDiff.inferLanguage(fileName), added, 0));
            }
        }
        return diffs;
    }
}

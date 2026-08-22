package com.codereview.agent.core.report;

import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质量趋势报告（见文档“周度质量报告 → 统计各类型问题趋势，输出给 Tech Lead”）。
 *
 * <p>基于 {@link ReviewHistoryStore} 聚合历史审查数据，输出 Markdown 报告，
 * 反映各严重级别问题趋势、高频规则与仓库分布，供技术负责人持续改进。
 */
public class QualityTrendReporter {

    private static final Logger log = LoggerFactory.getLogger(QualityTrendReporter.class);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ReviewHistoryStore historyStore;

    public QualityTrendReporter(ReviewHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    /**
     * 输出某团队最近 7 天的质量趋势报告。
     */
    public String reportWeekly(String teamId) {
        long since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        return build(historyStore.list(teamId).stream()
                .filter(e -> e.timestamp() >= since)
                .toList(), "最近 7 天");
    }

    /**
     * 输出某团队全部历史的质量趋势报告。
     */
    public String reportAll(String teamId) {
        return build(historyStore.list(teamId), "全部历史");
    }

    private String build(List<ReviewHistoryEntry> entries, String scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 📊 代码审查质量趋势报告（").append(scope).append("）\n\n");

        if (entries.isEmpty()) {
            sb.append("_暂无审查历史数据。触发一次 PR 审查后将自动累积。_\n");
            return sb.toString();
        }

        long totalReviews = entries.size();
        long totalFindings = entries.stream().mapToLong(e -> e.findings().size()).sum();

        // 严重级别分布
        Map<Severity, Long> sev = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            sev.put(s, 0L);
        }
        for (ReviewHistoryEntry e : entries) {
            for (var f : e.findings()) {
                sev.merge(f.severity(), 1L, Long::sum);
            }
        }

        // 高频规则 Top10
        Map<String, Long> rules = new LinkedHashMap<>();
        for (ReviewHistoryEntry e : entries) {
            for (var f : e.findings()) {
                rules.merge(f.ruleId(), 1L, Long::sum);
            }
        }
        List<Map.Entry<String, Long>> topRules = rules.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .toList();

        // 仓库分布
        Map<String, Long> repos = entries.stream()
                .collect(Collectors.groupingBy(ReviewHistoryEntry::repo, Collectors.counting()));

        sb.append("- **审查次数**：").append(totalReviews).append('\n');
        sb.append("- **发现问题总数**：").append(totalFindings).append('\n');
        sb.append("- **分级分布**：🔴 Blocker ").append(sev.get(Severity.BLOCKER))
                .append("，🟠 Major ").append(sev.get(Severity.MAJOR))
                .append("，🟡 Minor ").append(sev.get(Severity.MINOR))
                .append("，🔵 Info ").append(sev.get(Severity.INFO)).append("\n\n");

        sb.append("## 🔝 高频规则 Top ").append(topRules.size()).append("\n\n");
        sb.append("| 规则 | 命中次数 |\n|------|----------|\n");
        for (var r : topRules) {
            sb.append("| ").append(r.getKey()).append(" | ").append(r.getValue()).append(" |\n");
        }
        sb.append('\n');

        sb.append("## 📦 仓库分布\n\n");
        sb.append("| 仓库 | 审查次数 |\n|------|----------|\n");
        repos.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append('\n');

        sb.append("## 🕑 最近审查\n\n");
        entries.stream()
                .sorted(Comparator.comparingLong(ReviewHistoryEntry::timestamp).reversed())
                .limit(10)
                .forEach(e -> sb.append("- ")
                        .append(FMT.format(Instant.ofEpochMilli(e.timestamp())))
                        .append(" `").append(e.repo()).append("#").append(e.prId()).append("`")
                        .append(" runId=").append(e.runId(), 0, Math.min(8, e.runId().length()))
                        .append(" 发现 ").append(e.findings().size()).append(" 条\n"));

        return sb.toString();
    }
}

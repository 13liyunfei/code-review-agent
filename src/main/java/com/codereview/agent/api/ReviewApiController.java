package com.codereview.agent.api;

import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.report.QualityTrendReporter;
import com.codereview.agent.tenant.Teams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 人工介入与监控接口（见文档“Human-in-the-loop”与“监控与反馈机制”）。
 *
 * <p>提供：
 * <ul>
 *   <li>{@code POST /api/feedback}：开发者标记某条发现为误报 / 有效，沉淀到反馈闭环；</li>
 *   <li>{@code GET /api/feedback}：查看某团队历史反馈；</li>
 *   <li>{@code GET /api/quality-report}：获取某团队周度 / 全部质量趋势 Markdown 报告。</li>
 * </ul>
 *
 * <p>所有数据按 {@code X-Team-Id} 头（或 {@code team} 参数）隔离。仅当
 * {@code review.api.enabled=true} 时注册。生产环境建议配合网关鉴权暴露。
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "review.api.enabled", havingValue = "true", matchIfMissing = true)
public class ReviewApiController {

    private static final Logger log = LoggerFactory.getLogger(ReviewApiController.class);

    private final FeedbackStore feedbackStore;
    private final QualityTrendReporter qualityReporter;

    public ReviewApiController(FeedbackStore feedbackStore,
                              ReviewHistoryStore historyStore) {
        this.feedbackStore = feedbackStore;
        this.qualityReporter = new QualityTrendReporter(historyStore);
    }

    /**
     * 提交一条开发者反馈（误报标记 / 有效性确认）。
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam,
            @RequestBody FeedbackRequest request) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        if (request.ruleId() == null || request.ruleId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ruleId 不能为空"));
        }
        ReviewFeedback feedback = new ReviewFeedback(
                request.ruleId().trim(),
                request.agentType() == null ? "" : request.agentType().trim(),
                request.isFalsePositive(),
                request.note() == null ? "" : request.note().trim(),
                request.file() == null ? null : request.file().trim());
        feedbackStore.save(teamId, feedback);
        log.info("[API] 团队 {} 收到反馈：ruleId={}，agentType={}，误报={}，文件={}",
                teamId, feedback.ruleId(), feedback.agentType(), feedback.isFalsePositive(), feedback.file());
        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "team", teamId,
                "ruleId", feedback.ruleId(),
                "isFalsePositive", feedback.isFalsePositive()));
    }

    /**
     * 查看某团队全部反馈。
     */
    @GetMapping("/feedback")
    public ResponseEntity<Map<String, Object>> listFeedback(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return ResponseEntity.ok(Map.of(
                "team", teamId,
                "count", feedbackStore.list(teamId).size(),
                "items", feedbackStore.list(teamId)));
    }

    /**
     * 获取某团队质量趋势报告（Markdown 文本）。
     *
     * @param range week=最近 7 天，all=全部历史
     */
    @GetMapping(value = "/quality-report", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> qualityReport(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam,
            @RequestParam(defaultValue = "week") String range) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        String markdown = "all".equalsIgnoreCase(range)
                ? qualityReporter.reportAll(teamId)
                : qualityReporter.reportWeekly(teamId);
        return ResponseEntity.ok(markdown);
    }
}

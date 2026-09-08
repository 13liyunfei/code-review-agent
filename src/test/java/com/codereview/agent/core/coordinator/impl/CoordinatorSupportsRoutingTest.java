package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.enhance.ReviewEnhancements;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.report.ReportGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容相关性准入（supports() 谓词）路由验证。
 *
 * <p>背景：语义型 Agent（Logic/Perf/Style/Arch）对「纯文档/配置 PR」无代码可审，
 * 每轮仍全量下发 LLM 是纯 token 浪费。修复 = ReviewAgent 接口新增 default
 * {@code supports()}，语义型 Agent 覆写为 {@link CodeDiff#containsCodeFile}，
 * Coordinator 调度前按谓词剔除；Security 恒跑（注入防护对任意内容都有价值）。
 *
 * <p>本测试验证三层语义：
 * <ol>
 *   <li>{@link CodeDiff#containsCodeFile} 判定口径（unknown=非代码，其余视为代码）；</li>
 *   <li>纯文档 PR：覆写 supports 的 Agent 不被调用，默认（未覆写）Agent 仍执行；</li>
 *   <li>含代码 PR：全部 Agent 正常参与（谓词不误伤）。</li>
 * </ol>
 */
class CoordinatorSupportsRoutingTest {

    private static CodeDiff diff(String file, String language) {
        return new CodeDiff(file, "diff --git a/" + file + " b/" + file + "\n+line\n", language, 1, 0);
    }

    /** 覆写 supports = containsCodeFile 的「语义型」Agent（模拟 Logic/Perf/Style/Arch）。 */
    private static class SemanticStub implements ReviewAgent {
        final AgentType type;
        final AtomicInteger calls = new AtomicInteger();

        SemanticStub(AgentType type) {
            this.type = type;
        }

        @Override
        public AgentType getType() {
            return type;
        }

        @Override
        public boolean supports(List<CodeDiff> diffs, ReviewContext ctx) {
            return CodeDiff.containsCodeFile(diffs);
        }

        @Override
        public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            calls.incrementAndGet();
            return List.of();
        }
    }

    /** 未覆写 supports 的 Agent（默认恒 true）——模拟 Security 恒跑语义。 */
    private static class AlwaysAgent implements ReviewAgent {
        final AgentType type;
        final AtomicInteger calls = new AtomicInteger();

        AlwaysAgent(AgentType type) {
            this.type = type;
        }

        @Override
        public AgentType getType() {
            return type;
        }

        @Override
        public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            calls.incrementAndGet();
            return List.of();
        }
    }

    private static CompletableFutureCoordinator coordinator(List<ReviewAgent> agents) {
        return new CompletableFutureCoordinator(
                agents, new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                ForkJoinPool.commonPool(), null, null, ReviewEnhancements.none());
    }

    // ---------- 判定口径：containsCodeFile ----------

    @Test
    void containsCodeFileJudgesByLanguage() {
        // 纯文档 / 配置 / 无扩展名 → 非代码
        assertFalse(CodeDiff.containsCodeFile(List.of(diff("README.md", "unknown"))),
                "README.md（unknown）不应算代码文件");
        assertFalse(CodeDiff.containsCodeFile(List.of(
                        diff("README.md", "unknown"), diff("config/app.yml", "unknown"))),
                "全文档/配置不应算含代码");
        assertFalse(CodeDiff.containsCodeFile(null), "null 输入不应算含代码");
        assertFalse(CodeDiff.containsCodeFile(List.of()), "空列表不应算含代码");

        // 任一语言文件 → 含代码；xml/sql 等具体语言也视为代码（仍有语义审查价值）
        assertTrue(CodeDiff.containsCodeFile(List.of(diff("App.java", "java"))),
                "java 文件应算代码");
        assertTrue(CodeDiff.containsCodeFile(List.of(
                        diff("README.md", "unknown"), diff("schema.sql", "sql"))),
                "sql 属具体语言，应视为代码（SQL 注入/慢查询语义审查有价值）");
        assertTrue(CodeDiff.containsCodeFile(List.of(
                        diff("README.md", "unknown"), diff("pom.xml", "xml"))),
                "xml 属具体语言，应视为代码");
    }

    // ---------- Coordinator 调度：纯文档 PR ----------

    @Test
    void docOnlyPrSkipsSemanticAgentsButSecurityStillRuns() {
        SemanticStub logic = new SemanticStub(AgentType.LOGIC);
        SemanticStub style = new SemanticStub(AgentType.STYLE);
        // Security 恒跑：不覆写 supports（默认 true）
        AlwaysAgent security = new AlwaysAgent(AgentType.SECURITY);

        CompletableFutureCoordinator coordinator = coordinator(List.of(logic, style, security));
        PullRequest docPr = new PullRequest(7001, "demo/docs", "docs: update README", "@bob",
                "main", "default",
                List.of(diff("README.md", "unknown"), diff("config/app.yml", "unknown")),
                "sha-doc");

        ReviewReport report = coordinator.review(docPr);

        assertNotNull(report);
        assertEquals(0, logic.calls.get(), "纯文档 PR：逻辑 Agent 应按 supports()=false 跳过（省 token）");
        assertEquals(0, style.calls.get(), "纯文档 PR：规范 Agent 应按 supports()=false 跳过");
        assertEquals(1, security.calls.get(), "纯文档 PR：Security 恒跑（注入防护对任意内容都有价值）");
    }

    // ---------- Coordinator 调度：含代码 PR ----------

    @Test
    void codePrRunsAllAgentsNormally() {
        SemanticStub logic = new SemanticStub(AgentType.LOGIC);
        SemanticStub perf = new SemanticStub(AgentType.PERFORMANCE);
        AlwaysAgent security = new AlwaysAgent(AgentType.SECURITY);

        CompletableFutureCoordinator coordinator = coordinator(List.of(logic, perf, security));
        PullRequest codePr = new PullRequest(7002, "demo/code", "feat: add service", "@bob",
                "main", "default",
                List.of(diff("README.md", "unknown"), diff("src/OrderService.java", "java")),
                "sha-code");

        ReviewReport report = coordinator.review(codePr);

        assertNotNull(report);
        assertEquals(1, logic.calls.get(), "含代码 PR：逻辑 Agent 应参与");
        assertEquals(1, perf.calls.get(), "含代码 PR：性能 Agent 应参与");
        assertEquals(1, security.calls.get(), "含代码 PR：Security 参与");
    }
}

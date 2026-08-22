package com.codereview.agent.core.i18n;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外文案国际化：中英消息解析、占位符、审查报告语言切换。
 */
class ReviewMessagesTest {

    private static MessageSource source() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        return ms;
    }

    @Test
    void resolvesZhAndEnMessages() {
        // 中文（默认）
        new ReviewMessages("zh", source());
        assertEquals("多 Agent 协同代码审查报告", ReviewMessages.get("report.title"));
        assertEquals("安全审查", ReviewMessages.get("agent.type.SECURITY"));
        assertEquals("文件 `A.java` 第 3 行 · 规则 `R1`",
                ReviewMessages.get("autofix.itemFile", "A.java", 3, "R1"));

        // English
        new ReviewMessages("en", source());
        assertEquals("Multi-Agent Collaborative Code Review Report", ReviewMessages.get("report.title"));
        assertEquals("Security", ReviewMessages.get("agent.type.SECURITY"));
        assertEquals("File `A.java` line 3 · rule `R1`",
                ReviewMessages.get("autofix.itemFile", "A.java", 3, "R1"));
        assertEquals("Conflict arbitration (2 items decided by priority)",
                ReviewMessages.get("report.arbitration.title", 2));
    }

    @Test
    void fallbackToKeyWhenNoSource() {
        // 未注入 MessageSource（独立使用）时回退 key，不抛异常
        new ReviewMessages("zh");
        assertEquals("report.title", ReviewMessages.get("report.title"));
    }

    @Test
    void reportMarkdownFollowsConfiguredLanguage() {
        Finding f = new Finding(AgentType.SECURITY, "A.java", 1, 1, Severity.MAJOR, "security",
                "SEC-1", "Hardcoded secret", "desc", "use env var", 0.9, "RULE");
        ReviewReport report = new ReviewReport(1, "demo", List.of(f), Map.of(Severity.MAJOR, 1L),
                "run1", 123, List.of(), List.of(), List.of(), null);

        new ReviewMessages("zh", source());
        assertTrue(report.toMarkdown().contains("多 Agent 协同代码审查报告"), "中文模式应输出中文标题");
        assertTrue(report.toMarkdown().contains("严重"), "中文模式应输出中文级别名");

        new ReviewMessages("en", source());
        String en = report.toMarkdown();
        assertTrue(en.contains("Multi-Agent Collaborative Code Review Report"), "英文模式应输出英文标题");
        assertTrue(en.contains("Major"), "英文模式应输出英文级别名");
        assertTrue(en.contains("Security"), "英文模式审查方应为 Security");
        assertTrue(en.contains("Confidence"), "英文模式字段应为英文");
    }
}

package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询改写层测试（P0：diff 代码形态 → 规范术语形态的语义鸿沟弥合）。
 *
 * 覆盖：
 *  - IdentityQueryRewriter 恒等返回（默认 / 降级兜底，零依赖）；
 *  - LlmQueryRewriter 正常改写（去引号 / 截断首行）；
 *  - LLM 返回空 / 超长 → 视为无效回退原查询；
 *  - LLM 抛异常（网关熔断 / 超时）→ fail-safe 回退原查询，绝不阻断检索链路。
 */
class ReviewQueryRewriterTest {

    /** 可编程 LlmClient 桩（chat 返回预设 / 抛异常）。 */
    private static final class StubLlm implements LlmClient {
        private final String reply;
        private final RuntimeException error;
        private String lastPrompt;

        StubLlm(String reply) {
            this.reply = reply;
            this.error = null;
        }

        StubLlm(RuntimeException error) {
            this.reply = null;
            this.error = error;
        }

        @Override
        public String chat(String prompt) {
            this.lastPrompt = prompt;
            if (error != null) {
                throw error;
            }
            return reply;
        }
    }

    @Test
    void identityReturnsQueryUnchanged() {
        ReviewQueryRewriter rw = new IdentityQueryRewriter();
        String q = "String sql = \"select * from user where id=\" + id;";
        assertEquals(q, rw.rewrite(q));
        assertEquals("identity", rw.name());
    }

    @Test
    void llmRewritesToNormativeTerms() {
        StubLlm llm = new StubLlm("SQL 注入 预处理语句 prepared statement");
        ReviewQueryRewriter rw = new LlmQueryRewriter(llm);
        String out = rw.rewrite("String sql = \"select * from user where id=\" + id;");
        assertEquals("SQL 注入 预处理语句 prepared statement", out);
        assertTrue(llm.lastPrompt != null && llm.lastPrompt.contains("改写"));
    }

    @Test
    void llmStripsQuotesAndFirstLine() {
        // 模型常输出引号包裹或附带解释：都应被清洗为纯查询
        StubLlm quoted = new StubLlm("\"SQL 注入 参数绑定\"\n以上是改写结果");
        ReviewQueryRewriter rw = new LlmQueryRewriter(quoted);
        assertEquals("SQL 注入 参数绑定", rw.rewrite("jdbc sql concat"));
    }

    @Test
    void llmEmptyOutputFallsBackToRaw() {
        StubLlm blank = new StubLlm("   ");
        ReviewQueryRewriter rw = new LlmQueryRewriter(blank);
        String raw = "select * from t where a=1 拼接";
        assertEquals(raw, rw.rewrite(raw), "空白输出应回退原查询");
    }

    @Test
    void llmOverlongOutputFallsBackToRaw() {
        // 超长输出（模型把整段原文抄回来）应视为无效
        StubLlm verbose = new StubLlm("x".repeat(500));
        ReviewQueryRewriter rw = new LlmQueryRewriter(verbose);
        String raw = "some diff";
        assertEquals(raw, rw.rewrite(raw), "超长输出应回退原查询");
    }

    @Test
    void llmExceptionFallsBackToRawFailSafe() {
        StubLlm failing = new StubLlm(new IllegalStateException("网关熔断"));
        ReviewQueryRewriter rw = new LlmQueryRewriter(failing);
        String raw = "HttpClient post 明文传输";
        assertEquals(raw, rw.rewrite(raw), "LLM 异常必须回退，绝不向上抛");
    }

    @Test
    void nullQueryHandledGracefully() {
        ReviewQueryRewriter identity = new IdentityQueryRewriter();
        assertEquals("", identity.rewrite(null));
        StubLlm llm = new StubLlm("anything");
        assertEquals("", new LlmQueryRewriter(llm).rewrite(null), "null 输入不应触发 LLM 也不应 NPE");
    }
}

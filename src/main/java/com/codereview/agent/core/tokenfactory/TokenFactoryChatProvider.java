package com.codereview.agent.core.tokenfactory;

import com.codereview.agent.core.llm.TokenUsageRecord;
import com.codereview.agent.core.llm.TokenUsageRecorder;
import com.codereview.agent.core.llm.UsageAwareModelProvider;
import com.codereview.agent.core.trace.TraceContext;
import io.tokenfactory.client.TokenFactoryClient;
import io.tokenfactory.client.dto.ChatCompletionRequest;
import io.tokenfactory.client.dto.ChatCompletionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 走公司级 Token 工厂的模型供应商。
 *
 * <p>本系统不再直连真实厂商：请求打到工厂的 {@code /v1/chat/completions}，
 * 由工厂完成鉴权、额度判定、供应商路由、熔断重试与计价。
 * 本供应商只做三件事：透传链路 ID、读取响应里的用量、把失败如实抛出。
 *
 * <p><b>失败必须抛出去</b>：工厂不是兜底，它是主路径。它挂了就应该让网关 failover 到
 * 直连上游（见 {@code ReviewAgentConfig}），而不是在这里吞掉异常返回空串。
 */
public class TokenFactoryChatProvider implements UsageAwareModelProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenFactoryChatProvider.class);

    /** 供应商名前缀：日志里要一眼看出这通调用走的是工厂而不是直连。 */
    public static final String NAME_PREFIX = "token-factory:";

    private final String alias;
    private final TokenFactoryClient client;
    private final TokenUsageRecorder usageRecorder;
    private final boolean available;

    public TokenFactoryChatProvider(String alias, TokenFactoryClient client,
                                    TokenUsageRecorder usageRecorder, boolean available) {
        this.alias = alias;
        this.client = client;
        this.usageRecorder = usageRecorder;
        this.available = available;
    }

    @Override
    public String name() {
        return NAME_PREFIX + alias;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String chat(String prompt) throws Exception {
        return chatWithUsage(prompt).text();
    }

    @Override
    public ChatOutcome chatWithUsage(String prompt) throws Exception {
        long t0 = System.currentTimeMillis();
        ChatCompletionResponse response = client.chat(ChatCompletionRequest.builder(alias)
                .user(prompt)
                .build());
        long cost = System.currentTimeMillis() - t0;

        String text = response.content();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Token Factory 返回空内容（alias=" + alias + "）");
        }
        // 用量按工厂口径记：供应商填「实际命中的厂商」而不是别名，
        // 否则看板上全是 default，出问题时根本看不出是哪家的锅
        String actualProvider = response.provider() == null ? name() : response.provider();
        ChatCompletionResponse.Usage usage = response.usage();
        if (usageRecorder != null) {
            usageRecorder.record(TokenUsageRecord.of(
                    actualProvider,
                    response.upstreamModel() == null ? alias : response.upstreamModel(),
                    usage == null ? null : (int) usage.promptTokens(),
                    usage == null ? null : (int) usage.completionTokens(),
                    cost, true));
        }
        log.debug("[Token工厂] 调用成功：alias={}, 实际供应商={}, tokens={}, 费用={}微元, 耗时={}ms, traceId={}",
                alias, actualProvider,
                usage == null ? 0 : usage.totalTokens(), response.costMicros(), cost, response.traceId());

        return new ChatOutcome(text,
                usage == null ? null : (int) usage.promptTokens(),
                usage == null ? null : (int) usage.completionTokens());
    }

    /** 当前上下文的链路 ID；没有上下文时生成一个，保证溯源链不断。 */
    static String currentTraceId() {
        String traceId = TraceContext.getTraceId();
        return (traceId == null || traceId.isBlank()) ? TraceContext.newTraceId() : traceId;
    }
}

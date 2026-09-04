package com.codereview.agent.config;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.agent.impl.ArchitectureAgent;
import com.codereview.agent.core.agent.impl.LogicAgent;
import com.codereview.agent.core.agent.impl.PerformanceAgent;
import com.codereview.agent.core.agent.impl.SecurityAgent;
import com.codereview.agent.core.agent.impl.StyleAgent;
import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.admin.CustomAgentStore;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.kit.obs.AggregateTracer;
import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.coordinator.impl.CompletableFutureCoordinator;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.feedback.FileFeedbackStore;
import com.codereview.agent.core.history.FileReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.llm.BackoffPolicy;
import com.codereview.agent.core.llm.CircuitBreakerProvider;
import com.codereview.agent.core.llm.DefaultRetryClassifier;
import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.llm.LangChain4jChatProvider;
import com.codereview.agent.core.llm.LangChain4jEmbeddingClient;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.LlmGatewayProperties;
import com.codereview.agent.core.llm.LoggingChatModelListener;
import com.codereview.agent.core.llm.ModelGateway;
import com.codereview.agent.core.llm.ModelProvider;
import com.codereview.agent.core.llm.PriorityRouteStrategy;
import com.codereview.agent.core.llm.RetryClassifier;
import com.codereview.agent.core.llm.RouteStrategy;
import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.llm.TokenUsageRecorder;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.prompt.ClasspathPromptLoader;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.security.ContentInjectionDetector;
import com.codereview.agent.core.security.DiffInputGuard;
import com.codereview.agent.core.security.DiffInjectionDetector;
import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.core.security.KeywordInjectionDetector;
import com.codereview.agent.core.security.SemanticInjectionDetector;
import com.codereview.agent.core.security.StegInjectionScanner;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.skill.impl.HardcodedSecretSkill;
import com.codereview.agent.core.skill.impl.PatternSkill;
import com.codereview.agent.core.skill.impl.SqlInjectionSkill;
import com.codereview.agent.core.tokenfactory.TokenFactoryChatProvider;
import com.codereview.agent.core.tokenfactory.TokenFactoryClientHolder;
import com.codereview.agent.core.tokenfactory.TokenFactoryProperties;
import com.codereview.agent.core.tokenfactory.TokenFactoryUsageReporter;
import com.codereview.agent.core.tokenfactory.UsageReporter;
import com.codereview.agent.core.tokenfactory.UsageReportingProvider;
import com.codereview.agent.core.tool.ToolDefinition;
import com.codereview.agent.core.tool.ToolRouter;
import com.codereview.agent.core.http.EgressHttpClientFactory;
import com.codereview.agent.core.http.EgressProperties;
import com.codereview.agent.tenant.TeamProperties;
import com.codereview.agent.tenant.TeamResolver;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 多 Agent 代码审查系统装配配置。
 *
 * <p>集中声明各 Agent、协调者、工具与基础设施 Bean，并精确控制依赖注入
 * （如仅安全 Agent 挂载安全类 Skill），符合 Spring 最佳实践。
 */
@Configuration
@EnableConfigurationProperties({TeamProperties.class, TokenHubProperties.class,
        EgressProperties.class, LlmGatewayProperties.class, TokenFactoryProperties.class})
public class ReviewAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgentConfig.class);

    /**
     * 受控出口配置——所有外部 SaaS 出站依赖（LLM / Rerank）统一从此读取出口策略，
     * 取代原先脆弱的全局 bypass-system-proxy 布尔。内部服务（PG/Redis）不受影响。
     * 由类级 {@code @EnableConfigurationProperties(EgressProperties.class)} 绑定。
     */

    /**
     * 大模型客户端（LangChain4j 统一模型网关）：由 {@link TokenHubProperties} 驱动。
     *
     * <p>TokenHub 一个 API Key 可调用平台所有模型（OpenAI 兼容协议），故按
     * 「平台 + 模型」建模供应商：每个模型一个 {@link LangChain4jChatProvider}，
     * 共用同一 Key / base-url；模型按列表顺序路由，超时/失败时网关自动 failover。
     *
     * <p><b>2026-09-03 增强（对应 JD「高可用」「故障降级」）</b>：
     * <ul>
     *   <li>每个供应商默认包一层 {@link CircuitBreakerProvider}（连续失败 5 次 → OPEN 30s）；
     *       OPEN 期间快速失败，避免对已知不健康供应商继续浪费 RTT；</li>
     *   <li>{@link BackoffPolicy} 默认 200ms 起始 ×2 / 2s 上限，配合
     *       {@link DefaultRetryClassifier} 对临时错误（429/503/timeout）在同一供应商上退避重试，
     *       永久错误（401/403）立即切换；</li>
     *   <li>{@link RouteStrategy} 默认 {@link PriorityRouteStrategy}，
     *       跳过熔断 OPEN 中的供应商（与「顺序 failover」兼容）；</li>
     *   <li>{@link TokenUsageRecorder} 环形缓冲最近 1024 次调用，
     *       供 {@code LlmHealthController} 暴露 token 用量与调用次数。</li>
     * </ul>
     *
     * <p><b>无任何 Mock（2026-09-03）</b>：不注入假模型供应商，也不允许网关兜底——
     * 所有真实供应商失败即抛 {@code ModelUnavailableException}，由协调器把该 Agent
     * 标记为降级并在报告顶部告警。宁可「看不见」，绝不用假结果「看起来通过」。
     *
     * <p><b>公司级 Token 工厂接入（2026-09-03）</b>：当 {@code token-factory.enabled=true}
     * 时，候选列表里会多出一个 {@link TokenFactoryChatProvider}（按 {@code priority}
     * 决定排第一还是最后），其余直连供应商包一层 {@link UsageReportingProvider}
     * 把用量补报回工厂。降级路径因此是<b>天然的</b>——工厂失败就由网关 failover 到直连，
     * 不需要另写一套降级逻辑；代价只是「直连期间的用量得事后补报」，而这正是
     * {@link UsageReporter} 的职责。
     */
    @Bean
    public ModelGateway llmClient(TokenHubProperties tokenHub, LlmGatewayProperties gateway,
                                EgressProperties egress, AggregateTracer llmTracer,
                                TokenUsageRecorder usageRecorder,
                                TokenFactoryProperties factoryProps,
                                TokenFactoryClientHolder factoryClient) {
        UsageReporter reporter = factoryClient.reporter();
        List<ModelProvider> direct = new ArrayList<>();
        Duration timeout = Duration.ofSeconds(tokenHub.getTimeoutSeconds());
        for (TokenHubProperties.ModelSpec spec : tokenHub.getModels()) {
            LangChain4jChatProvider base = new LangChain4jChatProvider(spec.getName(),
                    buildOpenAiChatModel(egress, tokenHub.getBaseUrl(), tokenHub.getApiKey(), spec.getModel(),
                            timeout, llmTracer, usageRecorder),
                    tokenHub.hasKey());
            // 工厂供应商自己会计量，只有直连才需要补报
            direct.add(new UsageReportingProvider(base, reporter, factoryProps.getAlias(), spec.getModel()));
        }

        List<ModelProvider> providers = new ArrayList<>();
        TokenFactoryChatProvider factoryProvider = factoryProvider(factoryProps, factoryClient, usageRecorder);
        boolean factoryFirst = factoryProvider != null && factoryProps.isPriority();
        if (factoryFirst) {
            providers.add(wrapWithBreakerIfEnabled(gateway, factoryProvider));
        }
        for (ModelProvider p : direct) {
            providers.add(wrapWithBreakerIfEnabled(gateway, p));
        }
        if (factoryProvider != null && !factoryFirst) {
            providers.add(wrapWithBreakerIfEnabled(gateway, factoryProvider));
        }
        BackoffPolicy backoff = new BackoffPolicy(
                gateway.getRetry().getInitialBackoffMs(),
                gateway.getRetry().getMaxBackoffMs(),
                gateway.getRetry().getBackoffMultiplier());
        ModelGateway gatewayBean = new ModelGateway(providers, tokenHub.getQuotaPerMinute(),
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                backoff, gateway.getRetry().getMaxAttempts(),
                gateway.getRetry().isEnabled(), usageRecorder);
        log.info("已装配 LangChain4j 统一模型网关（{} + 熔断 + 退避重试）：{}",
                factoryProvider == null ? "TokenHub 多模型直连" : "Token 工厂优先 + TokenHub 直连兜底",
                gatewayBean.describe());
        return gatewayBean;
    }

    /** 工厂启用时构建工厂供应商；未启用返回 null（调用方据此决定是否加入候选列表）。 */
    private TokenFactoryChatProvider factoryProvider(TokenFactoryProperties props,
                                                     TokenFactoryClientHolder holder,
                                                     TokenUsageRecorder usageRecorder) {
        if (!props.isEnabled()) {
            return null;
        }
        if (!props.usable()) {
            // 开了开关却没配 AK：直接启动失败。半接不接的状态最难排查，
            // 而且会让人误以为「已经走工厂了」——实际上每次都在悄悄降级
            throw new IllegalStateException(
                    "token-factory.enabled=true 但未配置 token-factory.access-key："
                            + "请通过环境变量 TOKEN_FACTORY_KEY 注入，或把 token-factory.enabled 设为 false");
        }
        return new TokenFactoryChatProvider(props.getAlias(), holder.client(), usageRecorder, true);
    }

    /** 按配置决定是否包熔断器；关闭时 ModelGateway 行为与历史一致。 */
    private ModelProvider wrapWithBreakerIfEnabled(LlmGatewayProperties cfg, ModelProvider base) {
        if (!cfg.getCircuitBreaker().isEnabled()) {
            return base;
        }
        return new CircuitBreakerProvider(base,
                cfg.getCircuitBreaker().getFailureThreshold(),
                Duration.ofSeconds(cfg.getCircuitBreaker().getOpenSeconds()),
                cfg.getCircuitBreaker().getHalfOpenMaxTrials());
    }

    /**
     * 主聊天模型（供 AiServices 使用）：取 TokenHub 配置的第一个模型。
     *
     * <p><b>无 Key / 无模型即启动失败（fail-fast）</b>——不再提供 NoOp 占位：
     * 结构化审查路径没有「静默跳过」的余地，配置缺失应当立刻暴露而不是产出一份
     * 看起来正常的假报告。
     */
    @Bean
    public ChatModel primaryChatModel(TokenHubProperties tokenHub, EgressProperties egress, AggregateTracer llmTracer) {
        if (!tokenHub.hasKey()) {
            throw new IllegalStateException("未配置 tokenhub.api-key（已移除 Mock 兜底）："
                    + "请通过环境变量 TOKENHUB_API_KEY 注入真实 Key，服务拒绝在无模型配置下启动");
        }
        if (tokenHub.getModels().isEmpty()) {
            throw new IllegalStateException("tokenhub.models 为空：请至少声明一个模型（见 application.yml tokenhub.models）");
        }
        TokenHubProperties.ModelSpec spec = tokenHub.getModels().get(0);
        return buildOpenAiChatModel(egress, tokenHub.getBaseUrl(), tokenHub.getApiKey(), spec.getModel(),
                Duration.ofSeconds(tokenHub.getTimeoutSeconds()), llmTracer);
    }

    /**
     * LLM 调用指标聚合器（agent-kit 提供）。
     *
     * <p>由 {@link LoggingChatModelListener} 在模型边界喂 span，累计调用量 / 错误数 /
     * token / 耗时。日志侧的明细由监听器自己打，这里只做聚合，避免重复刷屏。
     * 需要对外暴露时注入本 bean 读 {@link AggregateTracer#snapshot()} 即可。
     */
    @Bean
    public AggregateTracer llmTracer() {
        return new AggregateTracer();
    }

    /**
     * Token 用量记录器（环形缓冲）：监听器把每次 LLM 调用的 token 用量写进来，
     * {@code LlmHealthController} 暴露给监控端点。
     */
    @Bean
    public TokenUsageRecorder tokenUsageRecorder() {
        return new TokenUsageRecorder(1024);
    }

    /**
     * LangChain4j AiServices：结构化审查 + 自动修复 + ChatMemory 短期记忆（按 Agent-团队-PR 隔离）。
     */
    @Bean
    public CodeReviewAiService codeReviewAiService(ChatModel primaryChatModel) {
        CodeReviewAiService service = AiServices.builder(CodeReviewAiService.class)
                .chatModel(primaryChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();
        log.info("已装配 LangChain4j AiServices（结构化输出 + ChatMemory 短期记忆）");
        return service;
    }

    /**
     * 向量化客户端：默认离线哈希嵌入；配置 {@code review.llm.embedding.enabled=true}
     * 时切换为 LangChain4j OpenAiEmbeddingModel 真实语义向量（api-key/base-url 未单独配置则复用 TokenHub）。
     * 注意：dim 仅用于日志与 pgvector.vector-dim 对齐提示，不传给模型（TokenHub kinfra 系列不支持 dimensions 参数）。
     */
    @Bean
    @Primary
    public EmbeddingClient embeddingClient(@Value("${review.llm.embedding.enabled:true}") boolean enabled,
                                           @Value("${review.llm.embedding.base-url:}") String baseUrl,
                                           @Value("${review.llm.embedding.api-key:}") String apiKey,
                                           @Value("${review.llm.embedding.model:kinfra-text-embedding-4b}") String model,
                                           @Value("${review.llm.embedding.dim:2560}") int dim,
                                           TokenHubProperties tokenHub,
                                           EgressProperties egress) {
        if (enabled) {
            String key = (apiKey == null || apiKey.isBlank()) ? tokenHub.getApiKey() : apiKey;
            String url = (baseUrl == null || baseUrl.isBlank()) ? tokenHub.getBaseUrl() : baseUrl;
            if (key != null && !key.isBlank()) {
                // 受控出口：注入按 review.egress.llm.mode 配置的 HttpClient（默认 DIRECT 直连）
                OpenAiEmbeddingModel em = OpenAiEmbeddingModel.builder()
                        .baseUrl(url).apiKey(key).modelName(model)
                        .httpClientBuilder(llmHttpClientBuilder(egress))
                        .build();
                log.info("已启用 LangChain4j 真实语义向量（model={}, 期望维度={}，请保持 pgvector.vector-dim 一致）", model, dim);
                return new LangChain4jEmbeddingClient(em);
            }
        }
        log.warn("未启用 LangChain4j 向量化，回退 SimpleHashEmbeddingClient（离线哈希嵌入，dim=256）");
        return new SimpleHashEmbeddingClient();
    }

    /**
     * RAG 重排器（质量闸门）：默认启发式（离线可用）；配置 {@code review.rag.rerank.enabled=true}
     * 且提供 API 时切换为 Cohere/Jina cross-encoder，失败自动降级启发式。
     */
    @Bean
    public com.codereview.agent.core.rag.Reranker reranker(
            EgressProperties egress,
            @Value("${review.rag.rerank.enabled:false}") boolean enabled,
            @Value("${review.rag.rerank.provider:cohere}") String provider,
            @Value("${review.rag.rerank.base-url:}") String baseUrl,
            @Value("${review.rag.rerank.api-key:}") String apiKey,
            @Value("${review.rag.rerank.model:rerank-english-v3.0}") String model,
            @Value("${review.rag.rerank.timeout-ms:2000}") int timeoutMs) {
        com.codereview.agent.core.rag.HeuristicReranker fallback =
                new com.codereview.agent.core.rag.HeuristicReranker();
        if (enabled && baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank()) {
            log.info("已启用 API 重排器（provider={}, model={}, 出口模式={}），失败自动降级启发式",
                    provider, model, egress.getRerank().getMode());
            return new com.codereview.agent.core.rag.ApiReranker(
                    provider, baseUrl, apiKey, model, timeoutMs, fallback, egress.getRerank());
        }
        log.info("RAG 重排器：使用启发式（离线可用；配置 review.rag.rerank.* 启用 API cross-encoder）");
        return fallback;
    }

    /**
     * RAG 评估与阈值过滤组件（选择性回答 abstain + 可观测）。
     * {@code review.rag.min-similarity} 默认 0.0（不拦截，向后兼容）；调高可抑制噪声块。
     */
    @Bean
    public com.codereview.agent.core.rag.RagEvaluator ragEvaluator(
            @Value("${review.rag.min-similarity:0.0}") double minSimilarity,
            @Value("${review.rag.eval-enabled:false}") boolean evalEnabled) {
        log.info("已装配 RagEvaluator（minSimilarity={}, evalEnabled={}）", minSimilarity, evalEnabled);
        return new com.codereview.agent.core.rag.RagEvaluator(minSimilarity, evalEnabled);
    }

    /**
     * 构造 LangChain4j 的 HTTP 客户端构造器（大模型网关出口）。
     *
     * <p>出口策略由 {@code review.egress.llm.mode} 显式声明（DIRECT/SYSTEM/PROXY），
     * 取代原先脆弱的全局 bypass-system-proxy 布尔。默认 DIRECT 直连（生产形态），
     * 若 {@code base-url} 指向公司内部 AI Gateway 则由网关统一管控供应商 key 与限流；
     * 开发机经 Clash 等本地代理出网时设 {@code mode=proxy} + {@code proxy-url}。
     */
    private dev.langchain4j.http.client.HttpClientBuilder llmHttpClientBuilder(EgressProperties egress) {
        java.net.http.HttpClient.Builder jdkBuilder =
                EgressHttpClientFactory.buildBuilder(egress.getLlm(), "llm");
        return new JdkHttpClientBuilder().httpClientBuilder(jdkBuilder);
    }

    private ChatModel buildOpenAiChatModel(EgressProperties egress, String baseUrl, String apiKey,
                                           String model, Duration timeout, AggregateTracer tracer,
                                           TokenUsageRecorder usageRecorder) {
        // 挂 LoggingChatModelListener：在模型边界统一记录 LLM 请求/响应（覆盖 AiServices 与文本两条路径），
        // 并把每次调用落成 agent-kit 的 GenAiSpan 交给聚合器（指标口径由基座统一，本仓库不自建）；
        // 同时把 token 用量落进环形缓冲供 SLA 端点暴露。
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.2)
                .timeout(timeout)
                .httpClientBuilder(llmHttpClientBuilder(egress))
                .listeners(new LoggingChatModelListener(tracer, usageRecorder))
                .build();
    }

    /** 兼容旧签名（primaryChatModel 直接调，没传 recorder 的场景）。 */
    private ChatModel buildOpenAiChatModel(EgressProperties egress, String baseUrl, String apiKey,
                                           String model, Duration timeout, AggregateTracer tracer) {
        return buildOpenAiChatModel(egress, baseUrl, apiKey, model, timeout, tracer, null);
    }

    /** 提示词模板加载器（classpath 模板文件）。 */
    @Bean
    public PromptTemplateLoader promptTemplateLoader() {
        return new ClasspathPromptLoader();
    }

    /**
     * diff 输入面注入检测器（关键词 + 隐写字符，可离线）。
     *
     * <p>2026-09-04 升级：由 {@link KeywordInjectionDetector} 换成
     * {@link DiffInjectionDetector}——补上隐写字符（零宽/Bidi 拆词藏指令）检测维度，
     * 使所有消费本 bean 的路径（如 Coordinator 展开自定义 Agent 的逐文件标注）自动具备
     * 该能力。需要 BLOCK/TAG 分级与文件级定位时用 {@link DiffInputGuard}（见 securityAgent）。
     */
    @Bean
    public InjectionDetector injectionDetector() {
        return new DiffInjectionDetector();
    }

    /**
     * diff 输入面注入防护门卫（SecurityAgent 专用）：逐文件 BLOCK/TAG/CLEAN 分级。
     *
     * <p>BLOCK（隐写字符 / 关键词 HIGH）→ 文件隔离不进 LLM；TAG（关键词 LOW / 语义近似）
     * → 渲染前显式标注；语义层只在新增内容短时触发（防稀释/延迟），embedding 不可达自动
     * 降级为空（不劣化）。详见 {@link DiffInputGuard}。
     */
    @Bean
    public DiffInputGuard securityInputGuard(EmbeddingClient embeddingClient) {
        return new DiffInputGuard(new KeywordInjectionDetector(), new StegInjectionScanner(),
                new SemanticInjectionDetector(embeddingClient));
    }

    /** 全部内置审查技能（项目自带，覆盖安全/逻辑/性能/规范/架构五个维度）。 */
    @Bean
    public List<Skill> allSkills() {
        return List.of(
                // ===== 安全 =====
                new HardcodedSecretSkill(),
                new SqlInjectionSkill(),
                // ===== 逻辑 =====
                new PatternSkill("empty-catch", "logic", "LOGIC-001", "空 catch 块吞掉异常",
                        "捕获异常后未做任何处理，可能掩盖业务逻辑错误。",
                        "至少记录日志；若确实可忽略，请添加注释说明原因。",
                        Severity.MAJOR, 0.95, Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}")),
                new PatternSkill("print-stack-trace", "logic", "LOGIC-002", "直接打印异常堆栈",
                        "printStackTrace 绕过了统一日志框架，不利于日志采集与问题定位。",
                        "使用 SLF4J 等日志框架记录异常：log.error(\"xxx\", e)。",
                        Severity.MAJOR, 0.95, Pattern.compile("e\\.printStackTrace\\s*\\(\\s*\\)")),
                new PatternSkill("system-out", "logic", "LOGIC-003", "使用 System.out 输出",
                        "生产代码应避免标准输出，交由统一日志组件管理级别与落盘。",
                        "替换为日志框架输出，并设置合适的日志级别。",
                        Severity.MAJOR, 0.95, Pattern.compile("System\\.out\\.print")),
                // ===== 性能 =====
                new PatternSkill("select-star", "performance", "PERF-001", "避免使用 SELECT *",
                        "全字段查询增加 IO 与网络开销，且不利于索引覆盖。",
                        "显式列出所需字段，并为高频查询字段建立索引。",
                        Severity.MAJOR, 0.95, Pattern.compile("(?i)select\\s+\\*\\s+from")),
                new PatternSkill("new-in-loop", "performance", "PERF-002", "循环体内创建对象",
                        "在循环内反复创建对象会增加 GC 压力，若可在循环外复用应提前初始化。",
                        "将不变的对象创建移出循环；集合考虑预分配容量。",
                        Severity.MAJOR, 0.95, Pattern.compile("(?i)(for|while)\\s*\\([^)]*\\)\\s*\\{?[^;]*new\\s+\\w+\\s*\\(")),
                new PatternSkill("synchronized-method", "performance", "PERF-003", "方法级 synchronized 可能成为并发瓶颈",
                        "粗粒度方法锁在高频调用下易引发线程竞争。",
                        "缩小锁粒度，或使用并发容器 / 读写锁等更灵活的同步手段。",
                        Severity.MINOR, 0.6, Pattern.compile("(?i)(public|protected|private)[\\s\\w]*\\bsynchronized\\b")),
                // ===== 规范 =====
                new PatternSkill("long-line", "style", "STYLE-001", "单行长度超过 120 字符",
                        "过长的代码行降低可读性，建议拆分或提取方法。",
                        "将长行拆分为多行或抽取为独立方法。",
                        Severity.MINOR, 0.9, Pattern.compile(".{121,}")),
                new PatternSkill("todo-marker", "style", "STYLE-002", "遗留 TODO / FIXME 标记",
                        "提交中仍包含待办标记，建议关联任务跟踪系统并明确责任人。",
                        "将待办项登记到 Issue / 需求系统，避免在代码中长期遗留。",
                        Severity.MAJOR, 0.95, Pattern.compile("(?i)//.*\\b(todo|fixme)\\b")),
                new PatternSkill("trailing-whitespace", "style", "STYLE-003", "行尾存在多余空白",
                        "行尾空白会引起无意义的 diff 噪声。",
                        "配置编辑器自动去除行尾空白。",
                        Severity.INFO, 0.9, Pattern.compile("\\s+$")),
                // ===== 架构 =====
                new PatternSkill("new-thread", "architecture", "ARCH-001", "直接 new Thread 创建线程",
                        "散落创建线程不利于统一监控与资源管控，易引发线程数失控。",
                        "使用统一线程池（如 Spring 的 TaskExecutor）提交异步任务。",
                        Severity.MAJOR, 0.95, Pattern.compile("\\bnew\\s+Thread\\s*\\(")),
                new PatternSkill("instanceof-check", "architecture", "ARCH-002", "使用 instanceof 进行类型判断",
                        "类型判断链往往意味着缺乏合理的多态抽象，扩展时需改动多处。",
                        "考虑以策略模式 / 多态分发替代 instanceof 分支。",
                        Severity.MINOR, 0.6, Pattern.compile("\\binstanceof\\b"))
        );
    }

    /**
     * 技能注册中心：统一管理内置与团队自定义技能，支持运行期启停与持久化。
     */
    @Bean
    public SkillRegistry skillRegistry(List<Skill> allSkills,
                                      @Value("${review.data-dir:./data}") String dataDir) {
        return new SkillRegistry(allSkills, Path.of(dataDir));
    }

    /**
     * 团队（租户）解析器：根据仓库 owner/repo 映射解析团队，未命中回退默认团队。
     * 由 {@link TeamProperties} 驱动（review.teams.default / review.teams.mapping）。
     */
    @Bean
    public TeamResolver teamResolver(TeamProperties teamProperties) {
        return new TeamResolver(teamProperties.getMapping(), teamProperties.getDefaultTeam());
    }

    /**
     * 自定义审查 Agent 存储（后管「自定义 Agent 列表」后端核心）。
     *
     * <p>按 teamId 隔离，落盘 <code>data-dir/&lt;teamId&gt;/custom-agents.json</code>；
     * 写库前用 {@link com.codereview.agent.core.security.ContentInjectionDetector}（异常填充 + 关键词 HIGH +
     * LOW 语义复核）对业务方提交内容做注入预检（命中即拒绝）——这是本仓库唯一把
     * 业务方文本「提升为系统提示内容」的边界，语义层在此才真正有价值。
     */
    @Bean
    public CustomAgentStore customAgentStore(EmbeddingClient embeddingClient,
                                            @Value("${review.data-dir:./data}") String dataDir) {
        return new CustomAgentStore(Path.of(dataDir), new ContentInjectionDetector(embeddingClient));
    }

    /** 工具定义（供 ToolRouter 注册与白名单路由）。 */
    @Bean
    public List<ToolDefinition> tools() {
        return ToolRouter.defaultTools();
    }

    @Bean
    public SecurityAgent securityAgent(LlmClient llmClient,
                                      PromptTemplateLoader promptLoader,
                                      SkillRegistry registry,
                                      ConfidenceCalibrationService calibration,
                                      DiffInputGuard securityInputGuard,
                                      CodeReviewAiService aiService) {
        return new SecurityAgent(llmClient, promptLoader, registry, calibration, securityInputGuard, aiService);
    }

    @Bean
    public LogicAgent logicAgent(LlmClient llmClient,
                                PromptTemplateLoader promptLoader,
                                SkillRegistry registry,
                                ConfidenceCalibrationService calibration,
                                CodeReviewAiService aiService) {
        return new LogicAgent(llmClient, promptLoader, registry, calibration, aiService);
    }

    @Bean
    public PerformanceAgent performanceAgent(LlmClient llmClient,
                                            PromptTemplateLoader promptLoader,
                                            SkillRegistry registry,
                                            ConfidenceCalibrationService calibration,
                                            CodeReviewAiService aiService) {
        return new PerformanceAgent(llmClient, promptLoader, registry, calibration, aiService);
    }

    @Bean
    public StyleAgent styleAgent(LlmClient llmClient,
                                PromptTemplateLoader promptLoader,
                                SkillRegistry registry,
                                ConfidenceCalibrationService calibration,
                                CodeReviewAiService aiService) {
        return new StyleAgent(llmClient, promptLoader, registry, calibration, aiService);
    }

    @Bean
    public ArchitectureAgent architectureAgent(LlmClient llmClient,
                                              PromptTemplateLoader promptLoader,
                                              SkillRegistry registry,
                                              ConfidenceCalibrationService calibration,
                                              CodeReviewAiService aiService) {
        return new ArchitectureAgent(llmClient, promptLoader, registry, calibration, aiService);
    }

    /** 全部审查 Agent（供 Coordinator 并行调度）。 */
    @Bean
    public List<ReviewAgent> reviewAgents(SecurityAgent securityAgent,
                                         LogicAgent logicAgent,
                                         PerformanceAgent performanceAgent,
                                         StyleAgent styleAgent,
                                         ArchitectureAgent architectureAgent,
                                         LlmClient llmClient,
                                         org.springframework.core.env.Environment env) {
        List<ReviewAgent> agents = List.of(securityAgent, logicAgent, performanceAgent, styleAgent, architectureAgent);
        // 工具增强织入（可选）：enabled 时每个内置 Agent 外包 ToolEquippedAgent（思考→调工具→观察→推理）
        if (Boolean.parseBoolean(env.getProperty("review.tools.agent-loop.enabled", "false"))
                && llmClient != null) {
            var registry = new com.codereview.kit.toolcalling.ToolRegistry();
            registry.register(new com.codereview.kit.toolcalling.BuiltinTools.CurrentTimeTool());
            registry.register(new com.codereview.kit.toolcalling.BuiltinTools.RegexScanTool());
            registry.register(new com.codereview.kit.toolcalling.BuiltinTools.FileReadTool(
                    java.nio.file.Path.of(env.getProperty("review.data-dir", "./data"))));
            var loop = new com.codereview.kit.toolcalling.ToolCallingLoop(llmClient, registry, 3);
            return agents.stream()
                    .map(a -> (ReviewAgent) new com.codereview.agent.core.toolcalling.ToolEquippedAgent(a, loop))
                    .toList();
        }
        return agents;
    }

    @Bean
    public ReportGenerator reportGenerator() {
        return new ReportGenerator();
    }

    /**
     * 对外文案国际化消息源（i18n/messages*.properties，中文默认 + 英文）。
     * 语言由 {@code review.lang=zh|en} 控制，见 {@link com.codereview.agent.core.i18n.ReviewMessages}。
     */
    @Bean
    public org.springframework.context.MessageSource messageSource() {
        org.springframework.context.support.ResourceBundleMessageSource ms =
                new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasename("i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        return ms;
    }

    /**
     * 置信度校准服务（反馈 → 规则准确率 → 校准）：注入规则准确率快照目录，
     * 使派生状态（ruleAccuracy）在重启后不丢失。
     *
     * <p>不再用 {@code @Service} 组件扫描——需要 {@code review.data-dir} 做快照持久化，
     * 由本方法显式装配，避免扫描路径拿不到配置。
     */
    @Bean
    public ConfidenceCalibrationService confidenceCalibrationService(
            @Value("${review.data-dir:./data}") String dataDir) {
        return new ConfidenceCalibrationService(Path.of(dataDir));
    }

    /**
     * 反馈存储（误报反馈闭环）：默认基于本地 JSON 文件持久化，目录不可用时回退内存。
     * 注入置信度校准服务作为落库监听器：每次保存反馈即驱动 markFalsePositive / markTruePositive，
     * 打通「反馈 → 规则准确率 → 置信度校准」闭环（此前校准恒为空转）。
     */
    @Bean
    public FeedbackStore feedbackStore(@Value("${review.data-dir:./data}") String dataDir,
                                       ConfidenceCalibrationService calibration) {
        return new FileFeedbackStore(Path.of(dataDir), calibration);
    }

    /**
     * 审查历史存储（修复后复检 / 质量趋势）：默认基于本地 JSON 文件持久化。
     */
    @Bean
    public ReviewHistoryStore reviewHistoryStore(@Value("${review.data-dir:./data}") String dataDir) {
        return new FileReviewHistoryStore(Path.of(dataDir));
    }

    @Bean
    public Coordinator coordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                  FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                  AdvancedAnalyzer advancedAnalyzer,
                                  @org.springframework.beans.factory.annotation.Qualifier("agentExecutor") Executor agentExecutor,
                                  com.codereview.agent.core.impact.ImpactAnalyzer impactAnalyzer,
                                  com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder trajectoryRecorder,
                                  com.codereview.agent.core.enhance.ReviewEnhancements enhancements,
                                  com.codereview.agent.core.memory.RagContextBuilder ragContextBuilder,
                                  CustomAgentStore customAgentStore,
                                  LlmClient llmClient,
                                  CodeReviewAiService codeReviewAiService,
                                  InjectionDetector injectionDetector,
                                  com.codereview.agent.core.analysis.index.ImpactIndexBuilder impactIndexBuilder,
                                  org.springframework.core.env.Environment environment) {
        // 任务规划织入（可选增强）：review.planning.enabled=true 时，LLM 先把审查目标拆解为
        // 子任务 DAG 再按依赖拓扑并行执行；默认关闭，行为与旧版完全一致
        com.codereview.agent.core.planning.TaskPlanningSupport planningSupport =
                new com.codereview.agent.core.planning.TaskPlanningSupport(
                        new com.codereview.kit.planning.TaskPlanner(llmClient),
                        new com.codereview.kit.planning.DagExecutor(agentExecutor),
                        Boolean.parseBoolean(environment.getProperty("review.planning.enabled", "false")));
        return new CompletableFutureCoordinator(agents, reportGenerator, feedbackStore,
                historyStore, advancedAnalyzer, agentExecutor, impactAnalyzer, trajectoryRecorder,
                enhancements, ragContextBuilder, customAgentStore, llmClient, codeReviewAiService, injectionDetector,
                planningSupport, impactIndexBuilder);
    }

    /**
     * 代码分析引擎路由（影响面索引用）。
     *
     * <p>{@code maxMethodLines=0} 表示不限制方法长度——影响面分析关心的是「谁调用了我」，
     * 与方法多长无关，加限制只会漏掉被长方法调用的场景。
     */
    @Bean
    public com.codereview.agent.core.analysis.index.AnalysisEngines analysisEngines() {
        return new com.codereview.agent.core.analysis.index.AnalysisEngines(0);
    }

    /**
     * 影响面索引构建器。
     *
     * <p>源码定位器是<b>可选</b>依赖：它由集成层（Gitea/GitLab）提供，
     * 未启用任何平台集成时这里拿不到实现，此时影响面分析自动降级（不产结论但不报错），
     * 不能因为少一个增强 Bean 就让整个应用起不来。
     *
     * @param locatorProvider 源码定位器（可能为空）
     * @param engines         引擎路由
     * @param environment     配置源
     */
    @Bean
    public com.codereview.agent.core.analysis.index.ImpactIndexBuilder impactIndexBuilder(
            org.springframework.beans.factory.ObjectProvider<
                    com.codereview.agent.core.analysis.index.RepoSourceLocator> locatorProvider,
            com.codereview.agent.core.analysis.index.AnalysisEngines engines,
            org.springframework.core.env.Environment environment) {
        int maxFiles = Integer.parseInt(environment.getProperty("review.impact.index-max-files", "200"));
        boolean samePackage = Boolean.parseBoolean(
                environment.getProperty("review.impact.index-same-package", "true"));
        boolean imports = Boolean.parseBoolean(
                environment.getProperty("review.impact.index-resolve-imports", "true"));
        return new com.codereview.agent.core.analysis.index.ImpactIndexBuilder(
                locatorProvider.getIfAvailable(),
                engines,
                new com.codereview.agent.core.analysis.index.IndexScope(samePackage, imports, maxFiles));
    }
}

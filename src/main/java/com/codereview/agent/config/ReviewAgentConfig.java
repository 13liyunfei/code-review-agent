package com.codereview.agent.config;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.agent.impl.ArchitectureAgent;
import com.codereview.agent.core.agent.impl.LogicAgent;
import com.codereview.agent.core.agent.impl.PerformanceAgent;
import com.codereview.agent.core.agent.impl.SecurityAgent;
import com.codereview.agent.core.agent.impl.StyleAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.coordinator.impl.CompletableFutureCoordinator;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.feedback.FileFeedbackStore;
import com.codereview.agent.core.history.FileReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.llm.LangChain4jChatProvider;
import com.codereview.agent.core.llm.LangChain4jEmbeddingClient;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.LoggingChatModelListener;
import com.codereview.agent.core.llm.MockProvider;
import com.codereview.agent.core.llm.ModelGateway;
import com.codereview.agent.core.llm.ModelProvider;
import com.codereview.agent.core.llm.NoOpChatModel;
import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.prompt.ClasspathPromptLoader;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.core.security.KeywordInjectionDetector;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.skill.impl.HardcodedSecretSkill;
import com.codereview.agent.core.skill.impl.PatternSkill;
import com.codereview.agent.core.skill.impl.SqlInjectionSkill;
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
@EnableConfigurationProperties({TeamProperties.class, TokenHubProperties.class, EgressProperties.class})
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
     * 未配置 Key 时仅剩 Mock 兜底，保证链路不中断。
     */
    @Bean
    public LlmClient llmClient(TokenHubProperties tokenHub, EgressProperties egress) {
        List<ModelProvider> providers = new ArrayList<>();
        Duration timeout = Duration.ofSeconds(tokenHub.getTimeoutSeconds());
        for (TokenHubProperties.ModelSpec spec : tokenHub.getModels()) {
            providers.add(new LangChain4jChatProvider(spec.getName(),
                    buildOpenAiChatModel(egress, tokenHub.getBaseUrl(), tokenHub.getApiKey(), spec.getModel(), timeout),
                    tokenHub.hasKey()));
        }
        providers.add(new MockProvider()); // 兜底终点，保证链路不中断
        ModelGateway gateway = new ModelGateway(providers, tokenHub.getQuotaPerMinute());
        log.info("已装配 LangChain4j 统一模型网关（TokenHub 多模型）：{}", gateway.describe());
        return gateway;
    }

    /**
     * 主聊天模型（供 AiServices 使用）：取 TokenHub 配置的第一个模型；无 Key 时为 NoOp 占位。
     */
    @Bean
    public ChatModel primaryChatModel(TokenHubProperties tokenHub, EgressProperties egress) {
        if (!tokenHub.hasKey() || tokenHub.getModels().isEmpty()) {
            return new NoOpChatModel();
        }
        TokenHubProperties.ModelSpec spec = tokenHub.getModels().get(0);
        return buildOpenAiChatModel(egress, tokenHub.getBaseUrl(), tokenHub.getApiKey(), spec.getModel(),
                Duration.ofSeconds(tokenHub.getTimeoutSeconds()));
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

    private ChatModel buildOpenAiChatModel(EgressProperties egress,
                                           String baseUrl, String apiKey, String model, Duration timeout) {
        // 挂 LoggingChatModelListener：在模型边界统一记录 LLM 请求/响应（覆盖 AiServices 与文本两条路径）
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.2)
                .timeout(timeout)
                .httpClientBuilder(llmHttpClientBuilder(egress))
                .listeners(new LoggingChatModelListener())
                .build();
    }

    /** 提示词模板加载器（classpath 模板文件）。 */
    @Bean
    public PromptTemplateLoader promptTemplateLoader() {
        return new ClasspathPromptLoader();
    }

    /** Prompt 注入检测器（关键词规则，可离线）。 */
    @Bean
    public InjectionDetector injectionDetector() {
        return new KeywordInjectionDetector();
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
                                      InjectionDetector injectionDetector,
                                      CodeReviewAiService aiService) {
        return new SecurityAgent(llmClient, promptLoader, registry, calibration, injectionDetector, aiService);
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
                                         ArchitectureAgent architectureAgent) {
        return List.of(securityAgent, logicAgent, performanceAgent, styleAgent, architectureAgent);
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
     * 反馈存储（误报反馈闭环）：默认基于本地 JSON 文件持久化，目录不可用时回退内存。
     */
    @Bean
    public FeedbackStore feedbackStore(@Value("${review.data-dir:./data}") String dataDir) {
        return new FileFeedbackStore(Path.of(dataDir));
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
                                  com.codereview.agent.core.memory.RagContextBuilder ragContextBuilder) {
        return new CompletableFutureCoordinator(agents, reportGenerator, feedbackStore,
                historyStore, advancedAnalyzer, agentExecutor, impactAnalyzer, trajectoryRecorder,
                enhancements, ragContextBuilder);
    }
}

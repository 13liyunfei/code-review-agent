package com.codereview.agent.core.skill;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.store.TeamConfigStore;
import com.codereview.agent.core.util.GlobMatcher;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 确定性规则解析器：<b>按文件模式</b>命中规则文档，四级覆盖链取内容。
 *
 * <p><b>它补的是 RAG 的补集，而不是替换 RAG。</b> 本项目的规范注入原本只有一条路：
 * 把 {@code handbook/java-coding-standard.md} 切块后入向量库，审查时按<b>语义相似度</b>召回。
 * 语义召回的天性是「可能漏」——改了一个 MyBatis Mapper XML，检索却未必召回 Mapper 规范；
 * 而「文件是 {@code *Mapper.xml}」这件事是<b>确定</b>的，用 glob 匹配就绝不会漏。
 * 两者叠加：RAG 负责「这个改动牵扯到什么知识」，本类负责「这类文件必须按什么规范看」。
 *
 * <p>四级覆盖链（优先级由高到低，同 id 时高优先级覆盖低优先级）：
 * <ol>
 *   <li>{@link RuleLevel#REQUEST} —— 本次审查显式指定的规则 id；</li>
 *   <li>{@link RuleLevel#TEAM} —— 按团队隔离存储的规则（{@link TeamConfigStore} 的 {@code rules} scope）；</li>
 *   <li>{@link RuleLevel#GLOBAL} —— 跨团队共享规则；</li>
 *   <li>{@link RuleLevel#BUILTIN} —— 随代码发布的 {@code resources/rules/}。</li>
 * </ol>
 *
 * <p><b>关键不变量——内置层不可被覆盖为空</b>：上三层只做「叠加 + 覆写同 id」，
 * 任何一层缺失、为空、解析失败都只降级为「这一层不贡献规则」，内置层永远可用。
 * 若允许「清空配置 = 没有规则」，审查会照常运行但不再报任何规范问题——
 * 这种失效没有任何报错，是本类最需要防住的一种。
 *
 * <p><b>解析失败不阻断审查</b>：团队规则 JSON 写坏了只记 WARN 并跳过该层；
 * 但绝不因为跳过而让内置层消失。
 */
@Component
public class RuleResolver {

    private static final Logger log = LoggerFactory.getLogger(RuleResolver.class);

    /** 内置规则索引（classpath 相对路径）。 */
    public static final String INDEX_RESOURCE = "rules/index.tsv";

    /** 团队 / 全局规则在 {@link TeamConfigStore} 中的 scope 名。 */
    public static final String CONFIG_SCOPE = "rules";

    /** 索引列数：pattern / ruleId / title。 */
    private static final int INDEX_COLUMNS = 3;

    private final TeamConfigStore configStore;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 内置规则（每条 = 一个 pattern 行）。 */
    private final List<BuiltinRule> builtinRules;

    /** ruleId -> 正文（内置层）。 */
    private final Map<String, String> builtinContent;

    /** 单次审查最多注入几条规则（防止把 prompt 撑爆）。 */
    @Value("${review.rules.max-per-review:5}")
    private int maxPerReview = 5;

    /** 单条规则正文注入上限（字符）；超出截断并在注入文本里显式标注。 */
    @Value("${review.rules.max-chars-per-rule:1200}")
    private int maxCharsPerRule = 1200;

    /** 总开关；关闭后不注入确定性规则（RAG 路径不受影响）。 */
    @Value("${review.rules.enabled:true}")
    private boolean enabled = true;

    /** 一条内置规则行。 */
    private record BuiltinRule(String pattern, String ruleId, String title) {
    }

    @Autowired
    public RuleResolver(@Autowired(required = false) TeamConfigStore configStore) {
        this.configStore = configStore;
        List<String> indexLines = readClasspathLines(INDEX_RESOURCE);
        List<BuiltinRule> rules = new ArrayList<>();
        Map<String, String> content = new LinkedHashMap<>();
        int broken = 0;
        for (String line : indexLines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] cols = s.split("\t");
            if (cols.length < INDEX_COLUMNS) {
                log.warn("[规则] 索引行格式非法（需 {} 列，TAB 分隔），已跳过：{}", INDEX_COLUMNS, s);
                broken++;
                continue;
            }
            String pattern = cols[0].strip();
            String ruleId = cols[1].strip();
            String title = cols[2].strip();
            if (pattern.isEmpty() || ruleId.isEmpty()) {
                log.warn("[规则] 索引行缺 pattern 或 ruleId，已跳过：{}", s);
                broken++;
                continue;
            }
            if (!content.containsKey(ruleId)) {
                String body = readClasspathText("rules/" + ruleId + ".md");
                if (body == null) {
                    // 索引指了不存在的正文：必须显式报出，否则表现为「规则配了却不生效」
                    log.warn("[规则] 索引声明的规则正文缺失，该 ruleId 全部模式已跳过：rules/{}.md", ruleId);
                    broken++;
                    continue;
                }
                content.put(ruleId, body);
            }
            rules.add(new BuiltinRule(pattern, ruleId, title));
        }
        this.builtinRules = Collections.unmodifiableList(rules);
        this.builtinContent = Collections.unmodifiableMap(content);
        log.info("[规则] 内置规则层加载完成：规则 {} 篇 / 模式 {} 条{}",
                content.size(), rules.size(), broken > 0 ? "（有 " + broken + " 行被跳过，见上文 WARN）" : "");
    }

    /** 便捷构造（单测 / 非 Spring 场景）：无团队配置存储。 */
    public RuleResolver() {
        this((TeamConfigStore) null);
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 按 diff 文件解析适用规则（四级覆盖链）。
     *
     * @param teamId 团队标识
     * @param diffs  代码变更
     * @return 命中的规则（已按层级与上限裁剪）
     */
    public List<RuleHit> resolve(String teamId, List<CodeDiff> diffs) {
        return resolve(teamId, diffs, List.of());
    }

    /**
     * 按 diff 文件解析适用规则，并支持请求级显式指定。
     *
     * @param teamId         团队标识
     * @param diffs          代码变更
     * @param requestRuleIds 本次审查显式指定的规则 id（可为 null）
     * @return 命中的规则（已按层级与上限裁剪）
     */
    public List<RuleHit> resolve(String teamId, List<CodeDiff> diffs, List<String> requestRuleIds) {
        if (!enabled) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        if (diffs != null) {
            for (CodeDiff d : diffs) {
                if (d != null && d.fileName() != null && !d.fileName().isBlank()) {
                    paths.add(GlobMatcher.normalize(d.fileName()));
                }
            }
        }
        if (paths.isEmpty() && (requestRuleIds == null || requestRuleIds.isEmpty())) {
            return List.of();
        }

        // LinkedHashMap = 按「层优先级 + 层内顺序」插入，天然就是最终顺序
        Map<String, RuleHit> hits = new LinkedHashMap<>();

        // 1) 请求级：显式指定，正文从任一层取（团队覆盖过的优先）
        if (requestRuleIds != null) {
            for (String id : requestRuleIds) {
                if (id == null || id.isBlank() || hits.containsKey(id)) {
                    continue;
                }
                String body = contentOf(id, teamId);
                if (body == null) {
                    log.warn("[规则] 请求级规则不存在，已忽略：{}", id);
                    continue;
                }
                hits.put(id, hit(id, titleOf(id, teamId), "<request>", RuleLevel.REQUEST, body));
            }
        }

        // 2) 团队级 / 3) 全局级（配置缺失或损坏只让该层不贡献，绝不影响内置层）
        collectFromConfig(hits, teamId, RuleLevel.TEAM, paths);
        collectFromConfig(hits, Teams.GLOBAL, RuleLevel.GLOBAL, paths);

        // 4) 内置层（永远可用）
        for (BuiltinRule r : builtinRules) {
            if (hits.size() >= Math.max(1, maxPerReview)) {
                break;
            }
            if (hits.containsKey(r.ruleId())) {
                continue;   // 同 id 已被更高层覆盖
            }
            if (!matches(paths, r.pattern())) {
                continue;
            }
            String body = builtinContent.getOrDefault(r.ruleId(), "");
            hits.put(r.ruleId(), hit(r.ruleId(), r.title(), r.pattern(), RuleLevel.BUILTIN, body));
        }

        List<RuleHit> result = new ArrayList<>(hits.values());
        if (result.size() > Math.max(1, maxPerReview)) {
            return result.subList(0, Math.max(1, maxPerReview));
        }
        return result;
    }

    /** 内置规则篇数（对外可观测：运维要能确认规则层真的加载了）。 */
    public int builtinRuleCount() {
        return builtinContent.size();
    }

    /** 内置规则模式条数。 */
    public int builtinPatternCount() {
        return builtinRules.size();
    }

    /** 内置规则 id 集合（索引自检用）。 */
    public java.util.Set<String> builtinRuleIds() {
        return Collections.unmodifiableSet(builtinContent.keySet());
    }

    // ------------------------------------------------------------------ 分层实现

    /** 从团队 / 全局配置层收集规则。 */
    private void collectFromConfig(Map<String, RuleHit> hits, String scopeOwner, RuleLevel level,
                                   List<String> paths) {
        Optional<String> json = readConfigJson(scopeOwner);
        if (json.isEmpty()) {
            return;
        }
        List<RuleHit> parsed = parseLayer(json.get(), level, paths);
        for (RuleHit h : parsed) {
            if (hits.size() >= Math.max(1, maxPerReview)) {
                return;
            }
            hits.putIfAbsent(h.ruleId(), h);
        }
    }

    /** 读配置（读失败只降级该层）。 */
    private Optional<String> readConfigJson(String scopeOwner) {
        if (configStore == null || scopeOwner == null || scopeOwner.isBlank()) {
            return Optional.empty();
        }
        try {
            return configStore.loadJson(Teams.sanitize(scopeOwner), CONFIG_SCOPE);
        } catch (Exception e) {
            log.warn("[规则] 读取 {} 级规则配置失败，该层不贡献规则（内置层不受影响）：{}",
                    scopeOwner, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析一层规则 JSON。
     *
     * <p>格式：{@code {"rules":[{"id":"...","title":"...","patterns":["..."],"content":"..."}]}}。
     * 解析失败返回空列表并记 WARN——<b>不让坏配置把审查整个打挂</b>，
     * 但也不静默：WARN 里带原因，便于定位。
     */
    private List<RuleHit> parseLayer(String json, RuleLevel level, List<String> paths) {
        List<RuleHit> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode rules = root.isArray() ? root : root.path("rules");
            if (!rules.isArray()) {
                log.warn("[规则] {} 级配置缺少 rules 数组，已忽略该层", level.code());
                return out;
            }
            for (JsonNode node : rules) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    log.warn("[规则] {} 级存在缺少 id 的规则，已忽略", level.code());
                    continue;
                }
                List<String> patterns = new ArrayList<>();
                for (JsonNode p : node.path("patterns")) {
                    patterns.add(p.asText());
                }
                String matched = firstMatch(paths, patterns);
                if (matched == null) {
                    continue;
                }
                String title = node.path("title").asText(id);
                String body = node.path("content").asText("");
                out.add(hit(id, title, matched, level, body));
            }
        } catch (Exception e) {
            log.warn("[规则] {} 级规则 JSON 解析失败，已忽略该层（内置层不受影响）：{}",
                    level.code(), e.getMessage());
        }
        return out;
    }

    /** 团队层优先、其次全局、最后内置：用于请求级规则取正文。 */
    private String contentOf(String ruleId, String teamId) {
        for (String owner : new String[]{teamId, Teams.GLOBAL}) {
            Optional<String> json = readConfigJson(owner);
            if (json.isEmpty()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(json.get());
                JsonNode rules = root.isArray() ? root : root.path("rules");
                for (JsonNode node : rules) {
                    if (ruleId.equals(node.path("id").asText(""))) {
                        String body = node.path("content").asText("");
                        if (!body.isBlank()) {
                            return body;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[规则] 解析 {} 的规则配置失败：{}", owner, e.getMessage());
            }
        }
        return builtinContent.get(ruleId);
    }

    /** 同上，取标题。 */
    private String titleOf(String ruleId, String teamId) {
        for (String owner : new String[]{teamId, Teams.GLOBAL}) {
            Optional<String> json = readConfigJson(owner);
            if (json.isEmpty()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(json.get());
                JsonNode rules = root.isArray() ? root : root.path("rules");
                for (JsonNode node : rules) {
                    if (ruleId.equals(node.path("id").asText(""))) {
                        return node.path("title").asText(ruleId);
                    }
                }
            } catch (Exception ignored) {
                // 已在 contentOf 里告警过；这里是尽力而为的取标题，不重复刷日志
            }
        }
        for (BuiltinRule r : builtinRules) {
            if (r.ruleId().equals(ruleId)) {
                return r.title();
            }
        }
        return ruleId;
    }

    private RuleHit hit(String ruleId, String title, String pattern, RuleLevel level, String body) {
        String content = body == null ? "" : body.strip();
        int limit = Math.max(0, maxCharsPerRule);
        boolean truncated = limit > 0 && content.length() > limit;
        if (truncated) {
            content = content.substring(0, limit);
        }
        return new RuleHit(ruleId, title, pattern, level, content, truncated);
    }

    private static boolean matches(List<String> paths, String pattern) {
        java.util.regex.Pattern p = GlobMatcher.compile(pattern);
        if (p == null) {
            return false;
        }
        for (String path : paths) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String firstMatch(List<String> paths, List<String> patterns) {
        for (String pattern : patterns) {
            if (matches(paths, pattern)) {
                return pattern;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ classpath 读取

    private static List<String> readClasspathLines(String resource) {
        String text = readClasspathText(resource);
        if (text == null) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }

    private static String readClasspathText(String resource) {
        ClassPathResource res = new ClassPathResource(resource);
        if (!res.exists()) {
            return null;
        }
        try (InputStream in = res.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[规则] 读取 classpath 资源失败：{}（{}）", resource, e.getMessage());
            return null;
        }
    }
}

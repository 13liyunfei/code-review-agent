package com.codereview.agent.core.gate;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.util.GlobMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * 送审闸门：决定「这次 PR 里哪些文件配得上模型的时间」。
 *
 * <p>借鉴 alibaba/open-code-review 的六道闸门（{@code internal/agent/selection.go}）分层与
 * 「密钥优先于用户规则」这条硬约束；<b>只借鉴设计，实现全部自写</b>（OCR 是 Apache-2.0，
 * 但这里用到的算法是 glob 匹配 + 短路求值，属于常识级，自己写反而更好维护）。
 *
 * <p><b>闸门顺序（短路求值，顺序本身是语义的一部分）</b>：
 * <pre>
 *   1. binary            二进制/富媒体        —— 安全类，不可放开
 *   2. secret_exclude    密钥/凭据路径        —— 安全类，不可放开（用户 include 也无法覆盖）
 *   3. allow             显式放行            —— 只能放开「策略类」闸门
 *   4. user_exclude      用户排除            —— 策略类
 *   5. not_included      include 白名单未命中  —— 策略类（include 为空则不启用）
 *   6. unsupported_ext   扩展名白名单未命中    —— 策略类（仅白名单模式开启时）
 *   7. default_path      构建产物/依赖目录     —— 策略类
 *   8. too_large         单文件体积            —— 成本类，默认关闭
 * </pre>
 *
 * <p><b>为什么密钥闸门排在 allow 之前</b>：这是本条设计里最容易做错、代价也最大的一处。
 * 如果把 allow / include 放在前面，开发者为了「让 CI 别老拦我的文件」加一条宽松规则，
 * 就会连带把 {@code .env} 放行——泄露是静默的，而审查被拦是吵闹的。把安全类闸门
 * 钉在策略类之前，等于让「放宽规则」这个动作在结构上不可能触碰机密。
 *
 * <p><b>为什么「被拦」也必须有原因</b>：见 {@link GateReason} 的类注释。此处只补充一点：
 * 闸门是本项目唯一一个「会让送审内容变少」的组件，因此它的每一次决策都必须可回读，
 * 否则就是给系统装了一个看不见的静音开关。
 */
@Component
public class PathGate {

    private static final Logger log = LoggerFactory.getLogger(PathGate.class);

    // ------------------------------------------------------------------ 内置清单

    /**
     * 二进制 / 富媒体扩展名。
     *
     * <p>内容是字节流，模型读不出语义，只会消耗 token 与上下文。注意 {@code svg} 不在其中——
     * 它是文本 XML，可审。
     */
    public static final Set<String> BINARY_EXT = Set.of(
            "jar", "war", "ear", "class", "exe", "dll", "so", "dylib", "a", "o", "obj", "lib", "pdb",
            "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "lz4",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "tiff", "tif", "psd", "ai", "eps",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
            "mp3", "mp4", "avi", "mov", "mkv", "wav", "flac", "ogg", "webm", "m4a", "m4v",
            "ttf", "otf", "woff", "woff2", "eot", "ttc",
            "bin", "dat", "pyc", "pyo", "pyd", "wasm", "classfile",
            "db", "sqlite", "sqlite3", "mdb", "parquet", "avro", "orc", "dump", "rdb", "aof",
            "snap", "img", "iso", "dmg", "pkg", "deb", "rpm", "msi", "hprof", "jfr");

    /**
     * 密钥 / 凭据路径（安全类）。
     *
     * <p>“优先级最高”是通过<b>闸门顺序</b>而不是通过“记得在别处也判一次”实现的：
     * 只有一处判定，就不存在“某个入口忘了判”这种漏。
     */
    public static final List<String> SECRET_PATTERNS = List.of(
            "**/.ssh/**", "**/.aws/**", "**/.gnupg/**", "**/.kube/config",
            "**/id_rsa", "**/id_rsa.*", "**/id_dsa*", "**/id_ecdsa*", "**/id_ed25519*",
            "**/.npmrc", "**/.yarnrc", "**/.pypirc", "**/.netrc", "**/.dockercfg",
            "**/.docker/config.json", "**/.git-credentials", "**/.htpasswd",
            "**/.env", "**/.env.*", "**/.envrc",
            "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore",
            "**/*.jceks", "**/*.asc", "**/*.gpg",
            "**/service-account*.json", "**/application-secret*.yml", "**/application-secret*.yaml",
            "**/application-secret*.properties", "**/secrets.yml", "**/secrets.yaml",
            "**/credentials", "**/credentials.json", "**/terraform.tfstate",
            "**/.terraform.lock.hcl");

    /**
     * 密钥闸门<b>内部</b>的例外：模板 / 样例文件本身按约定不含真实凭据，且本来就要提交与审查。
     *
     * <p>注意例外判定发生在密钥闸门<b>内部</b>，它不等于“用户可以用 include 覆盖密钥闸门”——
     * 后者仍然被禁止。这条例外是可枚举、可在代码评审里看清楚的；而“允许用户覆盖”
     * 是不可枚举的，那是两回事。
     */
    public static final List<String> SECRET_EXAMPLE_ALLOW = List.of(
            "**/*.example", "**/*.sample", "**/*.template", "**/*.tpl", "**/*.dist", "**/*.defaults");

    /**
     * 构建产物 / 依赖 / IDE 元数据目录名（按<b>路径段</b>匹配，不按 glob）。
     *
     * <p>按段匹配而不是 glob，是因为这些目录可能出现在任意深度；用 glob 表达
     * （{@code **}{@code /target/**}）既难写又容易漏。代价是目录同名即命中（例如某仓库
     * 真的把脚本放在 {@code bin/} 下），因此 {@code allow} 规则可以逐一放开。
     */
    public static final Set<String> NOISE_SEGMENTS = Set.of(
            "target", "build", "dist", "out", "bin", "obj",
            "node_modules", "bower_components", "vendor", "Pods", "DerivedData",
            ".git", ".gradle", ".idea", ".mvn", ".settings", ".vscode", ".fleet",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox", ".venv", "venv",
            ".eggs", "coverage", "htmlcov", ".nyc_output", ".cache",
            ".next", ".nuxt", ".parcel-cache", ".turbo", ".svelte-kit",
            ".terraform", ".serverless", ".pnpm-store");

    /** 可审查扩展名（仅当 {@code allow-list-enabled: true} 时生效）。 */
    public static final Set<String> REVIEWABLE_EXT = Set.of(
            "java", "kt", "kts", "groovy", "gradle", "scala", "go", "py", "rb", "php",
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "dart",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "rs", "swift", "m", "mm",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "sql", "xml", "yml", "yaml", "json", "jsonc", "properties", "toml", "ini", "conf", "cfg",
            "md", "txt", "rst", "adoc", "html", "htm", "css", "scss", "sass", "less",
            "tf", "tfvars", "proto", "graphql", "gql", "thrift", "avsc",
            "vue3", "cshtml", "jsp", "ftl", "vm", "hbs", "mustache", "liquid", "tpl");

    /** 无扩展名但应予审查的文件名（仅白名单模式相关）。 */
    public static final Set<String> REVIEWABLE_NAMES = Set.of(
            "dockerfile", "containerfile", "makefile", "gnumakefile", "jenkinsfile", "rakefile",
            "gemfile", "procfile", "brewfile", "justfile", "vagrantfile", "cmakelists.txt",
            "license", "notice", "authors", "contributors", "codeowners", "readme", "changelog");

    // ------------------------------------------------------------------ 实例状态

    private final PathGateProperties props;

    private final List<Pattern> secretPatterns;
    private final List<Pattern> secretAllowPatterns;
    private final List<Pattern> allowPatterns;
    private final List<Pattern> excludePatterns;
    private final List<Pattern> includePatterns;

    /** 原因编码 -> 累计拦截数（进程级；供 {@code /api/admin/gate/stats} 读取）。 */
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public PathGate(PathGateProperties props) {
        this.props = props;
        List<String> extra = props.getExtraSecretPatterns();
        List<String> allSecret = new ArrayList<>(SECRET_PATTERNS);
        if (extra != null) {
            allSecret.addAll(extra);
        }
        this.secretPatterns = compileAll(allSecret, true);
        this.secretAllowPatterns = compileAll(SECRET_EXAMPLE_ALLOW, true);
        this.allowPatterns = compileAll(props.getAllow(), false);
        this.excludePatterns = compileAll(props.getExclude(), false);
        this.includePatterns = compileAll(props.getInclude(), false);
    }

    /** 无参构造：默认配置（单测与非 Spring 场景用）。 */
    public PathGate() {
        this(new PathGateProperties());
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 对一组 diff 施加全部闸门。
     *
     * @param diffs 待送审的变更（可为 null/空）
     * @return 闸门报告（含送审清单、被拦清单与按原因聚合）
     */
    public GateReport apply(List<CodeDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return GateReport.of(List.of(), List.of());
        }
        if (!props.isEnabled()) {
            return GateReport.of(diffs, List.of());
        }
        List<CodeDiff> admitted = new ArrayList<>();
        List<GateDecision> decisions = new ArrayList<>(diffs.size());
        for (CodeDiff d : diffs) {
            if (d == null) {
                continue;
            }
            int patchChars = d.patch() == null ? 0 : d.patch().length();
            GateDecision dec = decide(d.fileName(), patchChars);
            decisions.add(dec);
            if (dec.admitted()) {
                admitted.add(d);
            } else {
                counters.computeIfAbsent(dec.reason().code(), k -> new LongAdder()).increment();
            }
        }
        GateReport report = GateReport.of(admitted, decisions);
        if (!report.blocked().isEmpty()) {
            log.info("[送审闸门] 拦下 {} 个文件（{}），送审 {} 个", report.blocked().size(),
                    report.summaryLine(), admitted.size());
        }
        return report;
    }

    /**
     * 单文件过闸判定（供测试与轨迹回放直接调用）。
     *
     * @param path       文件相对路径
     * @param patchChars 该文件 diff 的字符数（用于体积闸门；{@code <=0} 表示不参与体积判定）
     * @return 判定结果
     */
    public GateDecision decide(String path, int patchChars) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (!props.isEnabled()) {
            return GateDecision.admit(path);
        }

        String p = normalize(path);
        String base = basename(p);
        String ext = extensionOf(base);

        // 1) 二进制（安全类：内容层面就不可审）
        if (!ext.isEmpty() && BINARY_EXT.contains(ext)) {
            return GateDecision.block(path, GateReason.BINARY);
        }

        // 2) 密钥（安全类：优先级最高，用户 include / exclude / allow 一律无法覆盖）
        if (isSecret(p, base)) {
            return GateDecision.block(path, GateReason.SECRET_EXCLUDE);
        }

        // 3) allow：只能放开策略类闸门；放在安全类之后是刻意的（见类注释）
        if (matchesAny(allowPatterns, p)) {
            return GateDecision.admit(path);
        }

        // 4) 用户排除
        if (matchesAny(excludePatterns, p)) {
            return GateDecision.block(path, GateReason.USER_EXCLUDE);
        }

        // 5) include 白名单（为空则不启用；启用时「不在其中」即拦）
        if (!includePatterns.isEmpty() && !matchesAny(includePatterns, p)) {
            return GateDecision.block(path, GateReason.NOT_INCLUDED);
        }

        // 6) 扩展名白名单（仅显式开启时）
        if (props.isAllowListEnabled() && !isReviewableNameOrExt(base, ext)) {
            return GateDecision.block(path, GateReason.UNSUPPORTED_EXT);
        }

        // 7) 噪声目录
        if (inNoiseDir(p)) {
            return GateDecision.block(path, GateReason.DEFAULT_PATH);
        }

        // 8) 体积（成本类：默认关闭）
        if (props.getMaxDiffChars() > 0 && patchChars > props.getMaxDiffChars()) {
            return GateDecision.block(path, GateReason.TOO_LARGE);
        }

        return GateDecision.admit(path);
    }

    /** 读取并清零累计拦截计数（供指标端点使用）。 */
    public Map<String, Long> countersSnapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (GateReason r : GateReason.values()) {
            if (!r.blocked()) {
                continue;
            }
            LongAdder adder = counters.get(r.code());
            out.put(r.code(), adder == null ? 0L : adder.sum());
        }
        return out;
    }

    /** 配置是否启用。 */
    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** 当前生效的配置（只读用途；测试与端点回显）。 */
    public PathGateProperties properties() {
        return props;
    }

    // ------------------------------------------------------------------ 判据实现

    /**
     * 密钥判定：先看例外、再看清单。
     *
     * <p>顺序不能反——{@code .env.example} 同时命中 {@code **}{@code /.env.*} 与
     * {@code **}{@code /*.example}，若先判清单就直接拦掉了，而这类文件本来就是给人看的。
     */
    private boolean isSecret(String path, String base) {
        if (matchesAny(secretAllowPatterns, path)) {
            return false;
        }
        return matchesAny(secretPatterns, path) || matchesAny(secretPatterns, base);
    }

    /** 路径任一段命中噪声目录名即视为噪声。 */
    private boolean inNoiseDir(String path) {
        int from = 0;
        while (from <= path.length()) {
            int slash = path.indexOf('/', from);
            String seg = slash < 0 ? path.substring(from) : path.substring(from, slash);
            if (!seg.isEmpty() && NOISE_SEGMENTS.contains(seg)) {
                return true;
            }
            if (slash < 0) {
                break;
            }
            from = slash + 1;
        }
        return false;
    }

    /** 白名单模式下的可审查判定：认扩展名，也认 {@code Dockerfile} 这类无扩展名的名字。 */
    private boolean isReviewableNameOrExt(String base, String ext) {
        if (!ext.isEmpty()) {
            return REVIEWABLE_EXT.contains(ext);
        }
        return REVIEWABLE_NAMES.contains(base.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesAny(List<Pattern> patterns, String path) {
        return GlobMatcher.matchesAny(patterns, path);
    }

    /**
     * 委托给 {@link GlobMatcher}。
     *
     * <p>不在此处保留第二份 glob 实现：闸门与规则解析必须共用同一套模式语义，
     * 否则会出现「闸门认得的写法规则层不认」这类无报错的分裂。
     */
    private static List<Pattern> compileAll(List<String> globs, boolean caseInsensitive) {
        return GlobMatcher.compileAll(globs, caseInsensitive);
    }

    /** 统一路径形态（委托 {@link GlobMatcher#normalize}）。 */
    private static String normalize(String path) {
        return GlobMatcher.normalize(path);
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** 取小写扩展名；无扩展名返回空串。 */
    private static String extensionOf(String base) {
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return "";
        }
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

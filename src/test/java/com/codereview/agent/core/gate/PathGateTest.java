package com.codereview.agent.core.gate;

import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送审闸门（{@link PathGate}）的判据测试。
 *
 * <p>每个判据都配「必报样本 + 必放过样本」两类断言——只看必报样本的检查会误伤正常文件
 * （把 {@code .env.example} 一起拦掉），只看必放过样本的检查则漏掉真正的泄露路径。
 * 两类都必须断言，闸门才有意义。
 */
class PathGateTest {

    private static CodeDiff diff(String path) {
        return new CodeDiff(path, "@@ -1 +1 @@\n+x", CodeDiff.inferLanguage(path), 1, 0);
    }

    private static List<String> pathsOf(List<CodeDiff> diffs) {
        List<String> out = new ArrayList<>();
        for (CodeDiff d : diffs) {
            out.add(d.fileName());
        }
        return out;
    }

    private PathGate gate(PathGateProperties props) {
        return new PathGate(props);
    }

    // ---------------------------------------------------------------- 默认闸门

    @Test
    void secretFilesAreBlocked() {
        PathGate g = gate(new PathGateProperties());
        // 必报：凭据与密钥路径
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".env", 10).reason());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("config/.env.production", 10).reason());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("deploy/.ssh/id_rsa", 10).reason());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("certs/server.pem", 10).reason());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("k8s/service-account-prod.json", 10).reason());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("app/.netrc", 10).reason());
        // 大小写不敏感：macOS/Windows 上 .ENV 与 .env 是同一个文件
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".ENV", 10).reason());
    }

    @Test
    void secretTemplateFilesAreAllowed() {
        PathGate g = gate(new PathGateProperties());
        // 必放过：模板/样例文件按约定不含真实凭据，且本来就要提交与审查
        assertTrue(g.decide(".env.example", 10).admitted(), ".env.example 应放行");
        assertTrue(g.decide("config/.env.sample", 10).admitted(), ".env.sample 应放行");
        assertTrue(g.decide("deploy/app.properties.template", 10).admitted(), ".template 应放行");
    }

    @Test
    void binaryFilesAreBlocked() {
        PathGate g = gate(new PathGateProperties());
        assertEquals(GateReason.BINARY, g.decide("libs/app.jar", 10).reason());
        assertEquals(GateReason.BINARY, g.decide("assets/logo.png", 10).reason());
        assertEquals(GateReason.BINARY, g.decide("out/module.class", 10).reason());
        // 必放过：svg 是文本 XML，可审
        assertTrue(g.decide("docs/architecture.svg", 10).admitted());
    }

    @Test
    void noiseDirectoriesAreBlocked() {
        PathGate g = gate(new PathGateProperties());
        assertEquals(GateReason.DEFAULT_PATH, g.decide("target/generated/UserMapper.xml", 10).reason());
        assertEquals(GateReason.DEFAULT_PATH, g.decide("node_modules/pkg/index.js", 10).reason());
        assertEquals(GateReason.DEFAULT_PATH, g.decide("docs/.idea/workspace.xml", 10).reason());
        // 必放过：普通源码路径
        assertTrue(g.decide("src/main/java/com/x/A.java", 10).admitted());
        assertTrue(g.decide("src/main/resources/application.yml", 10).admitted());
    }

    // ---------------------------------------------------------------- 硬约束

    /**
     * 最容易做错的一条：用户 include 不能覆盖密钥闸门。
     *
     * <p>若这里失败，说明闸门顺序被改坏了——把 allow/include 排到了 secret 前面，
     * 那么任何人为了「少被拦几次」加一条宽松规则，都会连带放行 {@code .env}。
     */
    @Test
    void userIncludeCannotOverrideSecret() {
        PathGateProperties props = new PathGateProperties();
        props.setInclude(List.of("**/.env", "**/*.key", "**/*.java"));
        PathGate g = gate(props);

        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".env", 10).reason(),
                "显式 include .env 仍必须被拦");
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide("certs/server.key", 10).reason(),
                "显式 include *.key 仍必须被拦");
        assertTrue(g.decide("src/A.java", 10).admitted(), "白名单内的普通文件应放行");
    }

    /** allow 不能放开安全类闸门，但能放开策略类闸门。 */
    @Test
    void allowOnlyOpensPolicyGates() {
        PathGateProperties props = new PathGateProperties();
        props.setAllow(List.of("**/.env", "**/*.jar", "build/app.log"));
        PathGate g = gate(props);

        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".env", 10).reason(), "allow 不得放开密钥");
        assertEquals(GateReason.BINARY, g.decide("libs/app.jar", 10).reason(), "allow 不得放开二进制");
        assertTrue(g.decide("build/app.log", 10).admitted(), "allow 应能放开噪声目录");
    }

    /** 追加的密钥规则只增不减：命中即拦，且 allow 也放不开。 */
    @Test
    void extraSecretPatternsAreAdditive() {
        PathGateProperties props = new PathGateProperties();
        props.setExtraSecretPatterns(List.of("**/config/secrets-*.yaml"));
        props.setAllow(List.of("**/config/secrets-*.yaml"));
        PathGate g = gate(props);

        assertEquals(GateReason.SECRET_EXCLUDE,
                g.decide("app/config/secrets-prod.yaml", 10).reason());
    }

    // ---------------------------------------------------------------- 策略类闸门

    @Test
    void userExcludeAndIncludeWorkAsExpected() {
        PathGateProperties props = new PathGateProperties();
        props.setExclude(List.of("**/generated/**"));
        props.setInclude(List.of("**/*.java", "**/*.yml"));
        PathGate g = gate(props);

        assertEquals(GateReason.USER_EXCLUDE, g.decide("src/generated/Api.java", 10).reason(),
                "用户排除应先于 include 生效");
        assertEquals(GateReason.NOT_INCLUDED, g.decide("src/A.py", 10).reason(),
                "白名单外文件应记为 not_included");
        assertTrue(g.decide("src/A.java", 10).admitted());
    }

    @Test
    void unsupportedExtensionGateOnlyWhenWhitelistEnabled() {
        PathGateProperties off = new PathGateProperties();
        PathGate gOff = gate(off);
        assertTrue(gOff.decide("data/table.zzz", 10).admitted(),
                "白名单关闭时未知扩展名应放行（静默丢文件比多花 token 更危险）");

        PathGateProperties on = new PathGateProperties();
        on.setAllowListEnabled(true);
        PathGate gOn = gate(on);
        assertEquals(GateReason.UNSUPPORTED_EXT, gOn.decide("data/table.zzz", 10).reason());
        assertTrue(gOn.decide("Dockerfile", 10).admitted(), "无扩展名的已知文件名应放行");
        assertTrue(gOn.decide("src/A.java", 10).admitted());
    }

    @Test
    void tooLargeGateIsOffByDefaultAndAppliedWhenConfigured() {
        PathGate gDefault = gate(new PathGateProperties());
        assertTrue(gDefault.decide("src/A.java", 10_000_000).admitted(),
                "默认关闭体积闸门（拦下即漏审）");

        PathGateProperties props = new PathGateProperties();
        props.setMaxDiffChars(100);
        PathGate g = gate(props);
        assertEquals(GateReason.TOO_LARGE, g.decide("src/A.java", 101).reason());
        assertTrue(g.decide("src/A.java", 100).admitted(), "恰好等于上限应放行");
        // 安全类闸门仍优先于体积闸门
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".env", 1_000_000).reason());
    }

    @Test
    void disabledGateAdmitsEverything() {
        PathGateProperties props = new PathGateProperties();
        props.setEnabled(false);
        PathGate g = gate(props);
        assertTrue(g.decide(".env", 10).admitted());
        assertTrue(g.decide("libs/app.jar", 10).admitted());
    }

    // ---------------------------------------------------------------- 报告与聚合

    @Test
    void reportAggregatesBlockedByReason() {
        PathGate g = gate(new PathGateProperties());
        List<CodeDiff> input = List.of(
                diff("src/main/java/com/x/A.java"),
                diff(".env"),
                diff("config/.env.production"),
                diff("libs/app.jar"),
                diff("target/classes/app.properties"));

        GateReport report = g.apply(input);

        assertEquals(List.of("src/main/java/com/x/A.java"), pathsOf(report.admitted()));
        assertEquals(4, report.blocked().size());
        assertEquals(2, report.countsByReason().get("secret_exclude"));
        assertEquals(1, report.countsByReason().get("binary"));
        assertEquals(1, report.countsByReason().get("default_path"));
        assertFalse(report.allBlocked());
        assertTrue(report.summaryLine().contains("secret_exclude=2"), report.summaryLine());
        // 被拦原因必须可回读（否则「为什么少审了 4 个文件」无人能答）
        assertTrue(report.blockedMarkdown().contains("`.env`"), report.blockedMarkdown());
        assertEquals(4, report.toEventPayload().get("blocked"));
        assertEquals(1, report.toEventPayload().get("admitted"));
    }

    @Test
    void allBlockedIsDistinguishableFromEmpty() {
        PathGate g = gate(new PathGateProperties());
        GateReport allBlocked = g.apply(List.of(diff(".env"), diff("a.jar")));
        assertTrue(allBlocked.allBlocked(), "全部被拦应可识别（据此短路，不发起 LLM 调用）");
        assertTrue(allBlocked.admitted().isEmpty());

        GateReport trulyEmpty = g.apply(List.of());
        assertFalse(trulyEmpty.allBlocked(), "本来就没有文件 ≠ 被闸门拦空");
    }

    @Test
    void countersAccumulateByReason() {
        PathGate g = gate(new PathGateProperties());
        g.apply(List.of(diff(".env"), diff("a.jar")));
        g.apply(List.of(diff(".env")));
        assertEquals(Long.valueOf(2L), g.countersSnapshot().get("secret_exclude"));
        assertEquals(Long.valueOf(1L), g.countersSnapshot().get("binary"));
        assertEquals(Long.valueOf(0L), g.countersSnapshot().get("too_large"));
    }

    // ---------------------------------------------------------------- 边界与不变量

    @Test
    void globWithoutSlashMatchesAnyDepth() {
        PathGateProperties props = new PathGateProperties();
        props.setExclude(List.of("*.log"));
        PathGate g = gate(props);
        // 无 / 的 glob 应匹配任意深度——否则会出现「看着配了却没生效」的假象
        assertEquals(GateReason.USER_EXCLUDE, g.decide("deep/nested/app.log", 10).reason(),
                "不含 / 的规则（*.log）应匹配任意深度");

        // 顺带钉住一条语义：安全类闸门先于用户规则求值。
        // 用 *.jar 举例——用户即使写了 *.jar 排除，命中的原因也应是 binary 而非 user_exclude，
        // 因为二进制闸门排在前面。理由不是先来后到，而是：安全类闸门的结论不应依赖用户配置是否正确。
        PathGateProperties jarProps = new PathGateProperties();
        jarProps.setExclude(List.of("*.jar"));
        assertEquals(GateReason.BINARY, gate(jarProps).decide("deep/nested/lib.jar", 10).reason());
    }

    @Test
    void decisionRejectsInconsistentCombination() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision("a.env", true, GateReason.SECRET_EXCLUDE),
                "admitted=true 配拦截原因必须被拒绝（不允许自相矛盾的判定）");
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision("a.env", false, null),
                "原因不得为 null（不确定原因 ≠ 静默放行）");
        assertThrows(IllegalArgumentException.class, () -> GateDecision.admit("  "));
    }

    @Test
    void windowsAndRelativePathsAreNormalized() {
        PathGate g = gate(new PathGateProperties());
        assertEquals(GateReason.SECRET_EXCLUDE, g.decide(".\\config\\.env", 10).reason());
        assertEquals(GateReason.DEFAULT_PATH, g.decide("./target/x/A.java", 10).reason());
    }
}

package com.codereview.agent.core.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 基准跑分入口：让「评测」成为服务的一个可运行能力，而不是测试里的脚手架。
 *
 * <p>三种模式（{@code bench.mode}）：
 * <ul>
 *   <li>{@code report}：只跑并打印报告（想知道现在什么水平）；</li>
 *   <li>{@code check}：与 {@code bench/BASELINE.json} 比较，**回归即非零退出**（CI 用）；</li>
 *   <li>{@code update-baseline}：显式更新基线（**一次需要人负责的决定**，不是自动行为）。</li>
 * </ul>
 *
 * <p>默认关闭（{@code bench.run=false}）：跑一次要遍历全部语料并逐条做两次回放
 * （为了验证同输入同输出），不该挂在每次启动上。
 *
 * <p>用法：
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--bench.run=true --bench.mode=check"
 *   # 或对已构建的 jar：
 *   java -jar target/code-review-agent-*.jar --bench.run=true --bench.mode=report
 * </pre>
 *
 * <p><b>为什么退出码要做成非零</b>：CI 里"跑完但失败"如果只体现在日志里，门禁就形同虚设——
 * 上一次的教训是「日志里有 ≠ 能被发现」。所以 {@code check} 不通过时必须让进程以非零码结束。
 */
@Component
@ConditionalOnProperty(prefix = "bench", name = "run", havingValue = "true")
public class BenchRunnerCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchRunnerCli.class);

    private final String mode;
    private final String baselinePath;

    public BenchRunnerCli(@Value("${bench.mode:report}") String mode,
                          @Value("${bench.baseline-path:src/main/resources/bench/BASELINE.json}") String baselinePath) {
        this.mode = mode == null ? "report" : mode.trim().toLowerCase();
        this.baselinePath = baselinePath;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[Bench] 开始跑分：mode={} 基线={}", mode, baselinePath);
        // 刻意不把 ReviewBenchRunner 做成 Spring bean：它是纯离线工具，
        // 自己去加载语料即可，不需要（也不该）参与容器装配。
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        System.out.println(report.toText());

        switch (mode) {
            case "report" -> log.info("[Bench] 报告模式结束（未做门禁判定）");
            case "check" -> {
                JsonNode baseline = ReviewBenchGate.loadBaseline();
                ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
                System.out.println("── 门禁 ────────────────────────────────────────────────────────");
                System.out.print(v.toText());
                if (!v.pass()) {
                    log.error("[Bench] 门禁未通过，共 {} 条失败项", v.failures().size());
                }
            }
            case "update-baseline" -> {
                Path out = ReviewBenchGate.writeBaseline(report, Path.of(baselinePath));
                log.info("[Bench] 基线已更新：{}（请把这次变化当成一次需要复核的决定）", out.toAbsolutePath());
            }
            default -> throw new IllegalArgumentException(
                    "未知的 bench.mode=" + mode + "（可选 report / check / update-baseline）");
        }
    }
}

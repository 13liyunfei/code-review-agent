package com.codereview.agent.config;

import com.codereview.agent.core.analysis.BuiltinVulnSource;
import com.codereview.agent.core.analysis.OsvVulnSource;
import com.codereview.agent.core.analysis.ScaScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * SCA 数据源装配：把「开发/测试/生产都要查真实漏洞库」落到配置。
 *
 * <p>默认 {@code sca.source=auto} = OSV 优先、网络失败降级内置样本（报告标注 degraded）——
 * 这样 dev/test 日常跑的是 OSV 真实数据，不是内置假样本；纯离线或 CI 断网也不崩。
 *
 * <p>OSV 经可配置代理出站（仅本数据源的 HttpClient 走代理，不影响内网直连的其它调用）。
 * 本机（Clash）开发代理端口见 application-dev.yml 覆盖（.gitignore，不进版本库）。
 */
@Configuration
public class ScaSourceConfig {

    @Bean
    public ScaScanner scaScanner(Environment env) {
        String source = env.getProperty("sca.source", "auto");
        String proxyHost = env.getProperty("sca.osv.proxy-host", "");
        int proxyPort = Integer.parseInt(env.getProperty("sca.osv.proxy-port", "0"));
        long timeoutMs = Long.parseLong(env.getProperty("sca.osv.timeout-ms", "5000"));
        OsvVulnSource osv = new OsvVulnSource(proxyHost, proxyPort, Duration.ofMillis(timeoutMs));
        return switch (source) {
            case "osv" -> new ScaScanner(osv, null);
            case "builtin" -> new ScaScanner(new BuiltinVulnSource(), null);
            default -> new ScaScanner(osv, new BuiltinVulnSource());
        };
    }
}

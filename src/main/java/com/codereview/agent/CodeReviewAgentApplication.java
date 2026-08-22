package com.codereview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多 Agent 协同代码审查系统 —— 应用入口。
 *
 * <p>Web 服务应用，默认端口 8080。启动后：
 * <ul>
 *   <li>配置 gitlab.enabled=true 且提供 api-token 时，监听
 *       {@code POST /webhook/gitlab} 接收 MR Hook 并自动审查；</li>
 *   <li>配置 gitea.enabled=true 时，监听 {@code POST /webhook/gitea} 接收 PR Hook；</li>
 *   <li>配置 scan.enabled=true 时，按 cron 主动巡检目标分支（技术债务跟踪）；</li>
 *   <li>配置 demo.runner.enabled=true 时，启动时执行一次硬编码样例演示。</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class CodeReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeReviewAgentApplication.class, args);
    }
}

package com.codereview.agent.api;

import com.codereview.agent.config.CoreHealthIndicator;
import com.codereview.agent.config.CoreHealthIndicator.HealthResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点（零依赖，供容器编排存活/就绪探针）。
 *
 * <p>同时暴露 {@code GET /health} 与 {@code GET /actuator/health}（兼容约定路径）。
 * 依赖全部健康时返回 HTTP 200 + {@code {"status":"UP"}}；任一核心依赖异常返回 HTTP 503 + {@code DOWN}。
 */
@RestController
public class HealthController {

    private final CoreHealthIndicator indicator;

    public HealthController(CoreHealthIndicator indicator) {
        this.indicator = indicator;
    }

    @GetMapping({"/health", "/actuator/health"})
    public ResponseEntity<HealthResult> health() {
        HealthResult result = indicator.check();
        int code = "UP".equals(result.status) ? 200 : 503;
        return ResponseEntity.status(code).body(result);
    }
}

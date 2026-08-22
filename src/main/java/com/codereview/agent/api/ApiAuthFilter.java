package com.codereview.agent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API 访问鉴权过滤器（生产级安全基线）。
 *
 * <p>策略：
 * <ul>
 *   <li>放行 Webhook（{@code /webhook/**}，由 Gitea/GitLab 自有 HMAC 签名校验）、
 *       健康检查（{@code /health}、{@code /actuator/health}）与错误页；</li>
 *   <li>对 {@code /api/**}（含 {@code /api/admin/**}）强制 {@code Authorization: Bearer <token>}
 *       校验，token 取自 {@code review.api.auth-token}（通过环境变量 {@code REVIEW_API_TOKEN} 注入）；</li>
 *   <li>未配置 token 时降级为放行并告警（本地/开发便利，生产务必配置）；</li>
 *   <li>令牌比较采用常量时间，避免计时侧信道。</li>
 * </ul>
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    private final String authToken;

    public ApiAuthFilter(@Value("${review.api.auth-token:}") String authToken) {
        this.authToken = authToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // 放行：Webhook（自有签名校验）、健康检查、错误页、根路径
        if (isPassThrough(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // 仅对 /api/** 强制鉴权（含管理后台 /api/admin/**）
        if (uri.startsWith("/api/")) {
            if (!StringUtils.hasText(authToken)) {
                // 未配置 token：本地/开发便利，放行但持续告警（生产务必配置 REVIEW_API_TOKEN）
                log.warn("[ApiAuth] 未配置 review.api.auth-token，/api 接口当前零鉴权；"
                        + "生产环境请通过 REVIEW_API_TOKEN 设置访问令牌");
                chain.doFilter(request, response);
                return;
            }
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")
                    && constantTimeEquals(header.substring(7).trim(), authToken)) {
                chain.doFilter(request, response);
                return;
            }
            log.warn("[ApiAuth] 拒绝未授权访问：uri={}", uri);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Unauthorized: missing or invalid Authorization Bearer token\"}");
            return;
        }

        // 其它路径（如根路径）放行
        chain.doFilter(request, response);
    }

    private boolean isPassThrough(String uri) {
        return uri.startsWith("/webhook/")
                || uri.equals("/health")
                || uri.startsWith("/actuator/health")
                || uri.startsWith("/error")
                || uri.equals("/");
    }

    /** 常量时间比较，避免计时侧信道泄露 token 长度/内容。 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }
}

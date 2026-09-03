package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRetryClassifierTest {

    @Test
    void circuit_open_exception_is_permanent() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertFalse(c.isRetryable(new CircuitOpenException("p", java.time.Instant.now(), 30)));
    }

    @Test
    void illegal_argument_is_permanent() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertFalse(c.isRetryable(new IllegalArgumentException("bad arg")));
    }

    @Test
    void illegal_state_with_unconfigured_message_is_permanent() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertFalse(c.isRetryable(new IllegalStateException("未配置 api-key")));
    }

    @Test
    void timeout_exception_is_retryable() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertTrue(c.isRetryable(new java.net.SocketTimeoutException("timeout")));
        assertTrue(c.isRetryable(new java.util.concurrent.TimeoutException()));
    }

    @Test
    void connect_exception_is_retryable() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertTrue(c.isRetryable(new java.net.ConnectException("connection refused")));
    }

    @Test
    void http_status_markers_in_message_drive_retry() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertTrue(c.isRetryable(new RuntimeException("HTTP 429 too many requests")));
        assertTrue(c.isRetryable(new RuntimeException("upstream 503 unavailable")));
        assertTrue(c.isRetryable(new RuntimeException("upstream timeout")));
        assertFalse(c.isRetryable(new RuntimeException("HTTP 401 unauthorized")));
        assertFalse(c.isRetryable(new RuntimeException("HTTP 403 forbidden")));
        assertFalse(c.isRetryable(new RuntimeException("HTTP 400 format error")));
    }

    @Test
    void unknown_exception_defaults_to_retryable() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        // 保守：未识别 → 可重试（与历史失败转移一致）
        assertTrue(c.isRetryable(new RuntimeException("mysterious error")));
    }

    @Test
    void null_input_returns_false() {
        DefaultRetryClassifier c = new DefaultRetryClassifier();
        assertFalse(c.isRetryable(null));
    }
}
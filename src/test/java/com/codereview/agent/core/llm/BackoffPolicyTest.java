package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackoffPolicyTest {

    @Test
    void first_attempt_returns_initial() {
        BackoffPolicy p = new BackoffPolicy(200, 2000, 2.0);
        assertEquals(200, p.backoffMs(1));
    }

    @Test
    void exponential_backoff_doubles_each_attempt_until_capped() {
        BackoffPolicy p = new BackoffPolicy(100, 1000, 2.0);
        assertEquals(100, p.backoffMs(1));
        assertEquals(200, p.backoffMs(2));
        assertEquals(400, p.backoffMs(3));
        assertEquals(800, p.backoffMs(4));
        // 1600 > 1000, capped to 1000
        assertEquals(1000, p.backoffMs(5));
        assertEquals(1000, p.backoffMs(10));
    }

    @Test
    void linear_multiplier_one_yields_constant() {
        BackoffPolicy p = new BackoffPolicy(100, 1000, 1.0);
        assertEquals(100, p.backoffMs(1));
        assertEquals(100, p.backoffMs(5));
        assertEquals(100, p.backoffMs(10));
    }

    @Test
    void invalid_multiplier_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackoffPolicy(100, 1000, 0.5));
    }

    @Test
    void invalid_max_smaller_than_initial_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackoffPolicy(500, 100, 2.0));
    }

    @Test
    void sleep_zero_is_noop() {
        BackoffPolicy p = new BackoffPolicy(0, 1000, 2.0);
        // 不应抛、不应阻塞
        p.sleep(0);
    }
}
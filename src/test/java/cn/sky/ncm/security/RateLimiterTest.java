package cn.sky.ncm.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void limitsPerWindow() {
        var l = new RateLimiter();
        long t = 1_000_000;
        assertTrue(l.tryAcquire("ip:1", 2, t));
        assertTrue(l.tryAcquire("ip:1", 2, t + 1));
        assertFalse(l.tryAcquire("ip:1", 2, t + 2));
        assertTrue(l.tryAcquire("ip:2", 2, t + 2), "不同 key 互不影响");
        assertTrue(l.tryAcquire("ip:1", 2, t + RateLimiter.WINDOW_MS), "下一分钟重新计数");
    }

    @Test
    void zeroMeansUnlimited() {
        var l = new RateLimiter();
        for (int i = 0; i < 1000; i++) {
            assertTrue(l.tryAcquire("ip:1", 0));
        }
    }
}

package cn.sky.ncm.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 内存中的每分钟额度。配置值不大于零时不限流。 */
@Component
public class RateLimiter {

    static final int MAX_KEYS = 20_000;
    static final long WINDOW_MS = 60_000;
    private static final long CLEANUP_INTERVAL_MS = 1_000;

    private record Window(long start, int count) {
    }

    private final Map<String, Window> windows = new HashMap<>();
    private long nextCleanup;

    public boolean tryAcquire(String key, int limit) {
        return tryAcquire(key, limit, System.currentTimeMillis());
    }

    synchronized boolean tryAcquire(String key, int limit, long now) {
        if (limit <= 0) {
            return true;
        }
        Window window = windows.get(key);
        if (window == null && windows.size() >= MAX_KEYS) {
            if (now >= nextCleanup) {
                windows.values().removeIf(value -> now - value.start() >= WINDOW_MS);
                nextCleanup = now + CLEANUP_INTERVAL_MS;
            }
            if (windows.size() >= MAX_KEYS) {
                return false;
            }
        }
        if (window == null || now - window.start() >= WINDOW_MS) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (window.count() >= limit) {
            return false;
        }
        windows.put(key, new Window(window.start(), window.count() + 1));
        return true;
    }
}

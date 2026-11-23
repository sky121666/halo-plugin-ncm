package cn.sky.ncm.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 固定窗口（每分钟）计数的简单限流，存在内存里。limit &lt;= 0 表示不限制。 */
@Component
public class RateLimiter {

    static final int MAX_KEYS = 20_000;
    static final long WINDOW_MS = 60_000;

    private record Window(long start, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int limit) {
        return tryAcquire(key, limit, System.currentTimeMillis());
    }

    boolean tryAcquire(String key, int limit, long now) {
        if (limit <= 0) {
            return true;
        }
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
            if (windows.size() > MAX_KEYS) {
                windows.clear();
            }
        }
        Window w = windows.compute(key, (k, old) ->
            old == null || now - old.start() >= WINDOW_MS ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        return w.count() <= limit;
    }
}

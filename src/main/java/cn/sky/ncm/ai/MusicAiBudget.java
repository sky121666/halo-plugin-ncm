package cn.sky.ncm.ai;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
class MusicAiBudget {
    private static final int USER_REQUESTS_PER_MINUTE = 8;
    private static final int SITE_REQUESTS_PER_MINUTE = 30;
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();

    private final Semaphore concurrent = new Semaphore(2);
    private final Map<String, Integer> countByUser = new HashMap<>();
    private long windowStart = System.nanoTime();
    private int siteCount;

    <T> Mono<T> run(String username, Supplier<Mono<T>> request) {
        return Mono.defer(() -> {
            if (!concurrent.tryAcquire()) {
                return busy();
            }
            if (!take(username)) {
                concurrent.release();
                return busy();
            }
            return Mono.defer(request).doFinally(ignored -> concurrent.release());
        });
    }

    private synchronized boolean take(String username) {
        long now = System.nanoTime();
        if (now - windowStart >= WINDOW) {
            windowStart = now;
            countByUser.clear();
            siteCount = 0;
        }
        int userCount = countByUser.getOrDefault(username, 0);
        if (siteCount >= SITE_REQUESTS_PER_MINUTE || userCount >= USER_REQUESTS_PER_MINUTE) {
            return false;
        }
        siteCount++;
        countByUser.put(username, userCount + 1);
        return true;
    }

    private static <T> Mono<T> busy() {
        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 请求较多，请稍后再试"));
    }
}

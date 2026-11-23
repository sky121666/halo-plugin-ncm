package cn.sky.ncm.me;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 每个绑定账号共享一次喜欢列表查询。写入后失效，之前发出的请求无法填回新缓存。 */
@Component
public class LikeCache {

    static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_USERS = 5_000;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final LongSupplier clock;

    public LikeCache() {
        this(TTL, System::currentTimeMillis);
    }

    LikeCache(Duration ttl, LongSupplier clock) {
        this.ttlMs = ttl.toMillis();
        this.clock = clock;
    }

    public Mono<List<String>> get(String username, String uid, Supplier<Mono<List<String>>> load) {
        long now = clock.getAsLong();
        if (entries.size() >= MAX_USERS && !entries.containsKey(username)) {
            entries.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            if (entries.size() >= MAX_USERS) {
                entries.clear();
            }
        }
        return entries.compute(username, (key, current) -> {
            if (current != null && current.uid.equals(uid) && current.expiresAt > now) {
                return current;
            }
            return new Entry(uid, load);
        }).ids;
    }

    public void invalidate(String username) {
        entries.remove(username);
    }

    private final class Entry {
        final String uid;
        final Mono<List<String>> ids;
        volatile long expiresAt = Long.MAX_VALUE;

        Entry(String uid, Supplier<Mono<List<String>>> load) {
            this.uid = uid;
            ids = Mono.defer(load).map(List::copyOf)
                .doOnSuccess(value -> expiresAt = value == null ? 0 : clock.getAsLong() + ttlMs)
                .doOnError(error -> expiresAt = 0)
                .cache();
        }
    }
}

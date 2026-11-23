package cn.sky.ncm.me;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class LikeCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LikeCache cache = new LikeCache(Duration.ofSeconds(60), now::get);

    @Test
    void expiresAfterTtl() {
        assertEquals(List.of("1", "2"), get("alice", "101", "1", "2"));
        now.addAndGet(59_999);
        assertEquals(List.of("1", "2"), get("alice", "101", "3"));
        now.addAndGet(1);
        assertEquals(List.of("3"), get("alice", "101", "3"));
    }

    @Test
    void ignoresEntriesOfAnotherNcmAccount() {
        assertEquals(List.of("1"), get("alice", "101", "1"));
        assertEquals(List.of("2"), get("alice", "202", "2"));
        assertEquals(List.of("3"), get("bob", "101", "3"));
    }

    @Test
    void invalidateClearsOnlyThatUser() {
        get("alice", "101", "1");
        get("bob", "202", "2");
        cache.invalidate("alice");
        assertEquals(List.of("3"), get("alice", "101", "3"));
        assertEquals(List.of("2"), get("bob", "202", "4"));
    }

    @Test
    void queryStartedBeforeWriteCannotReplaceNewState() {
        Sinks.One<List<String>> upstream = Sinks.one();
        Mono<List<String>> old = cache.get("alice", "101", upstream::asMono);
        var pending = old.toFuture();
        assertSame(old, cache.get("alice", "101", () -> Mono.just(List.of("unneeded"))));
        cache.invalidate("alice");
        assertEquals(List.of("2"), get("alice", "101", "2"));
        upstream.tryEmitValue(List.of("1"));
        assertEquals(List.of("1"), pending.join());
        assertEquals(List.of("2"), get("alice", "101", "not loaded"));
    }

    private List<String> get(String user, String uid, String... ids) {
        return cache.get(user, uid, () -> Mono.just(List.of(ids))).block(Duration.ofSeconds(1));
    }
}

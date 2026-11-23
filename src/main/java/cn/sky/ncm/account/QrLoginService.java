package cn.sky.ncm.account;

import cn.sky.ncm.api.CustomApis;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.security.RateLimiter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 扫码会话绑定到发起人和目标账号。轮询只暂存凭据，由确认操作保存。 */
@Component
public class QrLoginService {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 1024;
    private static final RequestOptions ANONYMOUS = new RequestOptions("", Map.of(), null, null, false);

    private record Scope(String accountName, String owner) {
    }

    private static final class Attempt {
        final String key = UUID.randomUUID().toString();
        final String upstreamKey;
        final Instant expiresAt;
        List<String> cookies = List.of();
        Mono<ObjectNode> polling;
        boolean confirming;

        Attempt(String upstreamKey, Instant expiresAt) {
            this.upstreamKey = upstreamKey;
            this.expiresAt = expiresAt;
        }

        boolean expired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    private final CustomApis customApis;
    private final AccountService accounts;
    private final RateLimiter limiter;
    private final Clock clock;
    private final Map<Scope, Attempt> attempts = new HashMap<>();

    @Autowired
    public QrLoginService(CustomApis customApis, AccountService accounts, RateLimiter limiter) {
        this(customApis, accounts, limiter, Clock.systemUTC());
    }

    QrLoginService(CustomApis customApis, AccountService accounts, RateLimiter limiter, Clock clock) {
        this.customApis = customApis;
        this.accounts = accounts;
        this.limiter = limiter;
        this.clock = clock;
    }

    public Mono<ObjectNode> createQr(String accountName, String owner) {
        return Mono.defer(() -> {
            if (!limiter.tryAcquire("qr:" + owner, 6)) {
                return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再刷新二维码"));
            }
            return customApis.loginQrKey(ANONYMOUS).timeout(UPSTREAM_TIMEOUT).map(r -> {
                String upstreamKey = r.body().path("data").path("unikey").asText("");
                if (upstreamKey.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云暂时无法生成二维码");
                }
                String url = customApis.loginQrCreate(Map.of("key", upstreamKey))
                    .body().path("data").path("qrurl").asText("");
                if (url.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云暂时无法生成二维码");
                }
                Attempt attempt = register(new Scope(accountName, owner), upstreamKey);
                ObjectNode result = JsonNodeFactory.instance.objectNode();
                result.put("key", attempt.key);
                result.put("qrurl", url);
                return result;
            });
        });
    }

    private synchronized Attempt register(Scope scope, String upstreamKey) {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(e -> !e.getValue().confirming && e.getValue().expired(now));
        Attempt previous = attempts.get(scope);
        if (previous != null && previous.confirming) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "绑定正在保存");
        }
        if (previous == null && attempts.size() >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "扫码请求过多，请稍后重试");
        }
        Attempt attempt = new Attempt(upstreamKey, now.plus(TTL));
        attempts.put(scope, attempt);
        return attempt;
    }

    public Mono<ObjectNode> check(String key, String accountName, String owner) {
        return Mono.defer(() -> poll(new Scope(accountName, owner), key));
    }

    private synchronized Mono<ObjectNode> poll(Scope scope, String key) {
        Attempt attempt = current(scope, key);
        if (attempt == null) {
            return Mono.just(status(800, "二维码已过期，请刷新"));
        }
        if (!attempt.cookies.isEmpty()) {
            return Mono.just(status(803, "已授权，请确认绑定"));
        }
        if (attempt.polling == null) {
            attempt.polling = customApis.loginQrCheck(attempt.upstreamKey, ANONYMOUS)
                .timeout(UPSTREAM_TIMEOUT)
                .map(response -> checked(scope, attempt, response.body().path("code").asInt(), response.cookies()))
                .doFinally(signal -> pollingDone(attempt))
                .cache();
        }
        return attempt.polling;
    }

    private synchronized ObjectNode checked(Scope scope, Attempt attempt, int code, List<String> cookies) {
        if (current(scope, attempt.key) != attempt || code == 800) {
            attempts.remove(scope, attempt);
            return status(800, "二维码已过期，请刷新");
        }
        if (code == 803) {
            String session = AccountService.parseCookie(AccountService.setCookiesToHeader(cookies)).get("MUSIC_U");
            if (session == null || session.isBlank()) {
                attempts.remove(scope, attempt);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未返回登录信息，请刷新二维码");
            }
            attempt.cookies = List.copyOf(cookies);
            return status(803, "已授权，请确认绑定");
        }
        return status(code, code == 802 ? "请在网易云音乐 App 内确认" : "等待扫码");
    }

    private synchronized void pollingDone(Attempt attempt) {
        attempt.polling = null;
    }

    public Mono<ObjectNode> confirm(String key, String accountName, String owner) {
        return Mono.defer(() -> confirm(new Scope(accountName, owner), key));
    }

    private synchronized Mono<ObjectNode> confirm(Scope scope, String key) {
        Attempt attempt = current(scope, key);
        if (attempt == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.GONE, "扫码会话已结束，请刷新二维码"));
        }
        if (attempt.cookies.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "请先在网易云音乐 App 内授权"));
        }
        if (attempts.entrySet().stream().anyMatch(e ->
            e.getKey().accountName().equals(scope.accountName()) && e.getValue().confirming)) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "绑定正在保存"));
        }
        attempt.confirming = true;
        return Mono.defer(() -> accounts.save(scope.accountName(), scope.owner(), attempt.cookies))
            .flatMap(saved -> accounts.cookieOf(scope.accountName())
                .flatMap(cookie -> customApis.loginStatus(new RequestOptions("", cookie, null, null, false)))
                .flatMap(profile -> accounts.updateProfile(scope.accountName(), profile.body()))
                .timeout(UPSTREAM_TIMEOUT)
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty(saved))
            .map(saved -> {
                ObjectNode result = status(200, "绑定成功");
                result.set("account", AccountService.view(saved));
                return result;
            })
            .doOnSuccess(ignored -> finish(scope, attempt, true))
            .doOnError(ignored -> finish(scope, attempt, false))
            .cache();
    }

    private synchronized void finish(Scope scope, Attempt attempt, boolean saved) {
        if (saved) {
            attempt.cookies = List.of();
            attempts.remove(scope, attempt);
        } else {
            attempt.confirming = false;
        }
    }

    private Attempt current(Scope scope, String key) {
        Attempt attempt = attempts.get(scope);
        if (attempt == null || !attempt.key.equals(key)) {
            return null;
        }
        if (attempt.expired(clock.instant()) && !attempt.confirming) {
            attempts.remove(scope, attempt);
            return null;
        }
        return attempt;
    }

    private static ObjectNode status(int code, String message) {
        return JsonNodeFactory.instance.objectNode().put("code", code).put("message", message);
    }

    @PreDestroy
    synchronized void close() {
        attempts.clear();
    }
}

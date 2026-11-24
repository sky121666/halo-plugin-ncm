package cn.sky.ncm.finder;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.gateway.ResponseCache;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * ncmFinder 的数据来源：复用插件的接口描述和执行器，带缓存、并发合并和超时。
 * <ul>
 *   <li>{@link #call}：任何错误都返回空，不会让主题页面渲染失败；{@link #fetch}：出错时抛 {@link NcmUnavailableException}</li>
 *   <li>同一份数据在缓存期内只请求一次；同时到达的相同请求只发一次</li>
 *   <li>公开目录始终使用游客身份</li>
 * </ul>
 */
@Slf4j
@Component
public class FinderSource {

    static final Duration TIMEOUT = Duration.ofSeconds(6);

    private final ApiRegistry registry;
    private final ApiExecutor executor;
    private final SettingService settings;
    private final NcmBootstrap bootstrap;
    private final ResponseCache cache;
    private final Map<String, Mono<NcmResponse>> inflight = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    public FinderSource(ApiRegistry registry, ApiExecutor executor,
                        SettingService settings, NcmBootstrap bootstrap, ResponseCache cache) {
        this.registry = registry;
        this.executor = executor;
        this.settings = settings;
        this.bootstrap = bootstrap;
        this.cache = cache;
    }

    /**
     * 宽松调用（ncmFinder 用）：任何错误都返回空，不会让主题页面渲染失败。错误会记到日志（同一接口每分钟最多一条 WARN）。
     */
    public Mono<JsonNode> call(String api, Map<String, String> query) {
        return fetch(api, query).onErrorResume(e -> {
            warn(api, e);
            return Mono.empty();
        });
    }

    /**
     * 严格调用（插件自己的页面用）：200 返回数据；404 返回空；
     * 出错、超时（6 秒）、其他状态抛 {@link NcmUnavailableException}。
     */
    public Mono<JsonNode> fetch(String api, Map<String, String> query) {
        ApiDef def = registry.find(api).filter(ApiDef::executable).orElse(null);
        if (def == null) {
            return Mono.error(new NcmUnavailableException(api, "接口 " + api + " 不可用"));
        }
        return Mono.zip(settings.advanced(), settings.finder())
            .flatMap(t -> {
                int ttl = t.getT2().cacheTtl();
                String key = "finder|" + api + "|anonymous|" + new TreeMap<>(query);
                NcmResponse hit = cache.get(key);
                if (hit != null) {
                    return Mono.just(hit);
                }
                return inflight.computeIfAbsent(key, k -> bootstrap.ready()
                    .then(Mono.defer(() -> executor.execute(def, query, options(def, t.getT1()))))
                    .doOnNext(r -> {
                        if (r.status() == 200) {
                            cache.put(k, r, ttl);
                        }
                    })
                    .doFinally(s -> inflight.remove(k))
                    .cache());
            })
            .timeout(TIMEOUT)
            .onErrorMap(e -> !(e instanceof NcmUnavailableException),
                e -> new NcmUnavailableException(api, describe(e), e))
            .flatMap(r -> {
                if (r.status() == 200) {
                    return Mono.just(r.body());
                }
                if (r.status() == 404) {
                    return Mono.empty();
                }
                return Mono.error(new NcmUnavailableException(api, "网易云返回 " + r.status() + message(r)));
            });
    }

    static String describe(Throwable e) {
        if (e instanceof TimeoutException) {
            return "请求超时";
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    static String message(NcmResponse r) {
        var b = r.bodyObject();
        String m = b.path("msg").asText(b.path("message").asText(""));
        return m.isBlank() ? "" : "（" + m + "）";
    }

    void warn(String api, Throwable e) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(api);
        if (last == null || now - last > 60_000) {
            lastWarn.put(api, now);
            if (e.getCause() != null) {
                // 带上原始异常的堆栈，方便定位插件内部错误（例如空指针）
                log.warn("[ncm] ncmFinder 调用 {} 失败: {}", api, e.getMessage(), e.getCause());
            } else {
                log.warn("[ncm] ncmFinder 调用 {} 失败: {}", api, e.getMessage());
            }
        } else {
            log.debug("[ncm] ncmFinder 调用 {} 失败: {}", api, e.getMessage());
        }
    }

    static RequestOptions options(ApiDef def, AdvancedSetting s) {
        return new RequestOptions(def.crypto(), Map.of(), null, s.realIp(), s.useRandomCnIp());
    }
}

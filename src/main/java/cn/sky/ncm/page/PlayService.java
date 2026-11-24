package cn.sky.ncm.page;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.AccountService.Credentials;
import cn.sky.ncm.api.more.ToolApis;
import cn.sky.ncm.api.more.ToolApis.SongUrl;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.gateway.Identity;
import cn.sky.ncm.gateway.IdentityResolver;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 播放取址：每次请求时按当前访客决定用谁的网易云身份，并给出明确的结果（可播放 / 试听 / 没有音源 / 服务不可用）。
 * <pre>
 * 登录的 Halo 用户绑定了自己的网易云账号 → 用他自己的账号（会员歌曲可以听完整版）
 * 管理员本人没有单独绑定             → 用他在后台绑定的站长账号
 * 其他访客（含登录后未绑定的用户）   → 游客身份
 * </pre>
 * 成功和“没有音源”的结果按身份缓存一小段时间（不超过音频地址的有效期），服务出错不缓存。
 */
@Slf4j
@Component
public class PlayService {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_ENTRIES = 2000;
    static final Set<String> LEVELS =
        Set.of("standard", "exhigh", "lossless", "hires", "jyeffect", "vivid", "jymaster", "sky");

    /**
     * @param status     ok / unavailable（没有可用音源）/ service（网易云或插件出错）
     * @param url        音频地址（status 为 ok 时）
     * @param trialStart 试听片段在整首歌里的开始时间（毫秒，已从网易云的秒换算），不是试听时为 null
     * @param trialEnd   试听片段的结束时间（毫秒）
     * @param identity   user（访客自己的绑定）/ guest（游客）
     * @param message    给访客看的说明（status 不是 ok 时）
     */
    public record PlayResult(String status, String url, Long trialStart, Long trialEnd, String level, long br,
                             String identity, String message, Double gain) {

        public PlayResult(String status, String url, Long trialStart, Long trialEnd, String level, long br,
                          String identity, String message) {
            this(status, url, trialStart, trialEnd, level, br, identity, message, null);
        }

        public boolean ok() {
            return "ok".equals(status);
        }

        public boolean trial() {
            return trialEnd != null;
        }
    }

    record Who(Identity identity, String cacheKey, String label) {
        static Who bound(String kind, String scope, String label, Credentials credentials) {
            String revision = credentials.revision();
            return new Who(new Identity(kind, credentials.cookie()), revision == null ? null : scope + ":" + revision,
                label);
        }
    }

    private record Entry(PlayResult result, long expireAt) {
    }

    private final ToolApis tools;
    private final AccountService accounts;
    private final SettingService settings;
    private final NcmBootstrap bootstrap;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Mono<PlayResult>> inflight = new ConcurrentHashMap<>();

    public PlayService(ToolApis tools, AccountService accounts, SettingService settings, NcmBootstrap bootstrap) {
        this.tools = tools;
        this.accounts = accounts;
        this.settings = settings;
        this.bootstrap = bootstrap;
    }

    static String level(String level) {
        return level != null && LEVELS.contains(level) ? level : "standard";
    }

    /**
     * @param fresh 为 true 时不读缓存（播放器在音频加载失败后重试时使用）
     */
    public Mono<PlayResult> resolve(String id, String level, boolean fresh) {
        String lv = level(level);
        return settings.advanced().flatMap(s -> who().flatMap(w -> {
            if (w.cacheKey() == null) {
                return fetch(id, lv, s, w);
            }
            String key = "play|" + w.cacheKey() + "|" + id + "|" + lv;
            if (!fresh) {
                Entry e = cache.get(key);
                if (e != null && e.expireAt() > System.currentTimeMillis()) {
                    return Mono.just(e.result());
                }
            }
            return inflight.computeIfAbsent(key, k -> fetch(id, lv, s, w)
                .doOnNext(r -> store(k, r))
                .doFinally(x -> inflight.remove(k))
                .cache());
        }));
    }

    /** 云盘取址只使用已核验归属的绑定账号。 */
    public Mono<PlayResult> resolveForAccount(String id, String level, Map<String, String> cookie) {
        var who = new Who(new Identity("user", cookie), null, "user");
        return settings.advanced().flatMap(setting -> fetch(id, level(level), setting, who));
    }

    private Mono<PlayResult> fetch(String id, String level, AdvancedSetting setting, Who who) {
        return bootstrap.ready()
            .then(Mono.defer(() -> tools.resolveSongUrl(Map.of("id", id, "level", level),
                new RequestOptions("", who.identity().cookie(), null, setting.realIp(), setting.useRandomCnIp()))))
            .timeout(TIMEOUT)
            .map(url -> classify(url, level, who.label()))
            .onErrorResume(e -> {
                log.warn("[ncm] 获取歌曲 {} 的播放地址失败: {}", id, e.toString());
                return Mono.just(service(who.label(), e instanceof TimeoutException ? "请求网易云超时" : null));
            });
    }

    /** 当前请求用谁的身份。读取访客自己的绑定出错时退回游客身份。 */
    Mono<Who> who() {
        var guest = new Who(Identity.anonymous(), "guest", "guest");
        return IdentityResolver.currentUsername()
            .flatMap(username -> accounts.binding(username)
                .flatMap(binding -> Mono.justOrEmpty(binding.credentials()).map(credentials ->
                    Who.bound("user", (binding.site() ? "site-owner:" : "user:") + username, "user", credentials))))
            .onErrorResume(e -> {
                log.warn("[ncm] 读取访客的网易云绑定失败，改用游客身份: {}", e.toString());
                return Mono.empty();
            })
            .defaultIfEmpty(guest);
    }

    static PlayResult classify(SongUrl su, String level, String identity) {
        JsonNode item = su.item() == null ? MissingNode.getInstance() : su.item();
        var availability = su.availability();
        return switch (availability) {
            case FULL, TRIAL -> {
                boolean trial = availability == ToolApis.SongAvailability.TRIAL;
                JsonNode range = item.path("freeTrialInfo");
                double gain = item.path("gain").asDouble(Double.NaN);
                yield new PlayResult("ok", su.url(),
                    trial ? toMs(range.path("start").asDouble(0)) : null,
                    trial ? toMs(range.path("end").asDouble(0)) : null,
                    item.path("level").asText(level), item.path("br").asLong(0), identity, null,
                    Double.isFinite(gain) ? gain : null);
            }
            case UNAVAILABLE -> new PlayResult("unavailable", null, null, null, level, 0, identity,
                unavailableMessage(item, identity));
            case RATE_LIMITED -> rateLimited(identity);
            case SERVICE -> service(identity, su.responseCode() == 200
                ? "网易云返回的音源信息不完整" : "网易云返回 " + su.responseCode());
        };
    }

    /**
     * 网易云的 freeTrialInfo 用秒（例如 { start: 0, end: 30 }），插件对外统一用毫秒。
     * 个别接口可能已经是毫秒：大于 1000 时视为毫秒（试听片段不会超过 1000 秒）。
     */
    static long toMs(double v) {
        if (!(v > 0)) {
            return 0;
        }
        return v > 1000 ? Math.round(v) : Math.round(v * 1000);
    }

    static String unavailableMessage(JsonNode item, String identity) {
        int fee = item.path("fee").asInt(-1);
        String reason = switch (fee) {
            case 1 -> "会员歌曲";
            case 4 -> "付费专辑里的歌曲";
            default -> "没有版权或已下架";
        };
        String hint = "guest".equals(identity) && (fee == 1 || fee == 4) ? "，登录并绑定有权限的网易云账号后可以播放" : "";
        return "这首歌没有可用的音源（" + reason + hint + "）";
    }

    static PlayResult service(String identity, String detail) {
        return new PlayResult("service", null, null, null, null, 0, identity,
            "音乐服务暂时不可用" + (detail == null ? "" : "（" + detail + "）") + "，请稍后再试");
    }

    static PlayResult rateLimited(String identity) {
        return new PlayResult("rate_limited", null, null, null, null, 0, identity, "请求太频繁，请稍等一会儿再播放");
    }

    void store(String key, PlayResult r) {
        long ttlMs;
        if (r.ok()) {
            // 音频地址一般 20 分钟后失效；缓存 10 分钟，取出时还有约 10 分钟有效期
            ttlMs = 10 * 60_000L;
        } else if ("unavailable".equals(r.status())) {
            ttlMs = 2 * 60_000L;
        } else {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(r, System.currentTimeMillis() + ttlMs));
    }
}

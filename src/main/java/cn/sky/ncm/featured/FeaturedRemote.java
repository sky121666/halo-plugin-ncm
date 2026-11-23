package cn.sky.ncm.featured;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.more.ToolApis;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.gateway.Identity;
import cn.sky.ncm.gateway.IdentityResolver;
import cn.sky.ncm.setting.SettingService;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 歌单发布用到的网易云请求。身份由调用方明确指定：游客（{@link Identity#anonymous()}，不受“访客使用站长账号”设置影响）
 * 或站长账号。不经过 FinderSource 的缓存，核对结果总是最新的。
 */
@Slf4j
@Component
public class FeaturedRemote {

    static final Duration TIMEOUT = Duration.ofSeconds(12);
    static final Duration SHORT_TIMEOUT = Duration.ofSeconds(6);
    static final String SHORT_BASE = "https://163cn.tv/";
    static final Pattern SHORT_CODE = Pattern.compile("^[A-Za-z0-9]{4,16}$");
    static final String UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final ApiRegistry registry;
    private final ApiExecutor executor;
    private final IdentityResolver resolver;
    private final SettingService settings;
    private final NcmBootstrap bootstrap;
    private final ToolApis tools;
    private final NcmHttp http;

    public FeaturedRemote(ApiRegistry registry, ApiExecutor executor, IdentityResolver resolver,
                          SettingService settings, NcmBootstrap bootstrap, ToolApis tools, NcmHttp http) {
        this.registry = registry;
        this.executor = executor;
        this.resolver = resolver;
        this.settings = settings;
        this.bootstrap = bootstrap;
        this.tools = tools;
        this.http = http;
    }

    /** playlist_detail。 */
    public Mono<NcmResponse> detail(String playlistId, Identity who) {
        return call("playlist_detail", Map.of("id", playlistId), who);
    }

    /** playlist_track_all 前 1000 首（带版权信息 privileges）。 */
    public Mono<NcmResponse> tracks(String playlistId, Identity who) {
        return call("playlist_track_all", Map.of("id", playlistId, "limit",
            String.valueOf(FeaturedCheck.MAX_COUNTED), "offset", "0"), who);
    }

    /** 站长账号；没绑定或读取失败时为空。 */
    public Mono<Identity> site() {
        return resolver.site().onErrorResume(e -> Mono.empty());
    }

    /** 用游客身份真实取一次音频地址（和 /music/play 用同一个取址过程）。 */
    public Mono<ToolApis.SongUrl> probe(String songId) {
        return settings.basic().flatMap(s -> bootstrap.ready().then(Mono.defer(() -> tools.resolveSongUrl(
                Map.of("id", songId, "level", "standard"),
                new RequestOptions("", Identity.anonymous().cookie(), null, s.realIp(), s.useRandomCnIp())))))
            .timeout(TIMEOUT);
    }

    Mono<NcmResponse> call(String api, Map<String, String> query, Identity who) {
        ApiDef def = registry.find(api).filter(ApiDef::executable).orElse(null);
        if (def == null) {
            return Mono.error(new IllegalStateException("接口 " + api + " 不可用"));
        }
        return settings.basic().flatMap(s -> bootstrap.ready().then(Mono.defer(() -> executor.execute(def, query,
                new RequestOptions(def.crypto(), who.cookie(), null, s.realIp(), s.useRandomCnIp())))))
            .timeout(TIMEOUT);
    }

    // ---------- 短链 ----------

    /**
     * 展开 163cn.tv 短链，直接返回识别结果，不会抛错。
     * <ul>
     *   <li>只请求固定地址 {@code https://163cn.tv/{code}}（code 为 4~16 位字母数字，主机和路径都由插件拼出）</li>
     *   <li>只请求一次，不跟随跳转，只读 Location</li>
     *   <li>Location 必须是网易云歌单页面（{@link FeaturedInput#parseLocation}）；跳到短链、其他站点、内网地址一律拒绝</li>
     * </ul>
     */
    public Mono<FeaturedInput.Parsed> expandShort(String code) {
        if (code == null || !SHORT_CODE.matcher(code).matches()) {
            return Mono.just(FeaturedInput.Parsed.error("bad_short", "短链格式不正确"));
        }
        return http.current().get()
            .uri(URI.create(SHORT_BASE + code))
            .header("User-Agent", UA)
            .exchangeToMono(resp -> {
                int status = resp.statusCode().value();
                List<String> loc = resp.headers().header("Location");
                FeaturedInput.Parsed parsed = status >= 300 && status < 400
                    ? location(loc.isEmpty() ? null : loc.get(0))
                    : FeaturedInput.Parsed.error("bad_short", "短链无效或已失效");
                return resp.releaseBody().thenReturn(parsed);
            })
            .timeout(SHORT_TIMEOUT)
            .onErrorResume(e -> {
                log.info("[ncm] 展开短链 {} 失败: {}", code, e.toString());
                return Mono.just(FeaturedInput.Parsed.error("short_failed",
                    "暂时无法展开短链（" + describe(e) + "），请稍后再试，或者粘贴完整的歌单链接"));
            });
    }

    /** 校验跳转地址：相对地址按短链站点解析（结果仍是短链，会被拒绝），只接受网易云的歌单页面。 */
    static FeaturedInput.Parsed location(String raw) {
        if (raw == null || raw.isBlank()) {
            return FeaturedInput.parseLocation(null);
        }
        String abs;
        try {
            abs = URI.create(SHORT_BASE).resolve(raw.strip()).toString();
        } catch (IllegalArgumentException e) {
            return FeaturedInput.Parsed.error("redirect_rejected", "短链跳转到了无法识别的地址，已拒绝");
        }
        return FeaturedInput.parseLocation(abs);
    }

    static String describe(Throwable e) {
        if (e instanceof TimeoutException) {
            return "请求超时";
        }
        return e.getClass().getSimpleName();
    }

}

package cn.sky.ncm.page;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.finder.NcmUnavailableException;
import cn.sky.ncm.finder.vo.LyricLineVo;
import cn.sky.ncm.finder.vo.LyricVo;
import cn.sky.ncm.gateway.IdentityResolver;
import cn.sky.ncm.gateway.NcmGateway;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.SettingService;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Folia 使用的播放取址和歌词接口。
 * <pre>
 * GET /music/play/{id}?level=&amp;format=json&amp;fresh=1
 *     播放地址。默认 302 跳转到音频（可以直接放进 audio 的 src）；format=json 时返回
 *     { id, status, url, trial: { start, end } | null, level, br, identity, message }。
 *     status：ok（200）/ unavailable 没有可用音源（404）/ service 服务不可用（503）/ rate_limited（429）。
 *     身份每次请求时决定，见 {@link PlayService}。fresh=1 时不读缓存。
 * GET /music/lyric/{id}   歌词 JSON：{ id, instrumental, lines: [{ t（毫秒）, text, tr（译文，可能没有） }] }
 *     网易云出错时 503（error: "unavailable"）。
 * </pre>
 * 都按访客限流：登录用户按用户名（「高级 → 个人接口每分钟请求数」），其他按 IP（「公开接口每分钟请求数」），超出时 429。
 * 播放地址每首歌会请求两次（音频和信息），所以次数按两倍计算。
 */
@Component
public class PlayerRouter {

    static final int MAX_LINES = 1000;
    static final String LYRIC_CACHE = "public, max-age=86400";

    private final SettingService settings;
    private final NcmFinderImpl finder;
    private final RateLimiter limiter;
    private final PlayService plays;

    public PlayerRouter(SettingService settings, NcmFinderImpl finder, RateLimiter limiter, PlayService plays) {
        this.settings = settings;
        this.finder = finder.strict();
        this.limiter = limiter;
        this.plays = plays;
    }

    @Bean
    RouterFunction<ServerResponse> ncmPlayerRouter() {
        return route(GET("/music/lyric/" + MusicPageRouter.ID_PATTERN), this::lyric)
            .andRoute(GET("/music/play/" + MusicPageRouter.ID_PATTERN), this::play);
    }

    Mono<ServerResponse> play(ServerRequest r) {
        String id = r.pathVariable("id");
        String level = r.queryParam("level").orElse(null);
        boolean asJson = "json".equals(r.queryParam("format").orElse(""));
        boolean fresh = r.queryParam("fresh").isPresent();
        return settings.advanced().flatMap(adv -> IdentityResolver.currentUsername()
            .map(u -> new String[] {"user:" + u, String.valueOf(adv.userLimit())})
            .defaultIfEmpty(new String[] {"ip:" + NcmGateway.clientIp(r, adv),
                String.valueOf(adv.publicLimit())})
            .flatMap(k -> {
                int limit = Integer.parseInt(k[1]);
                if (!limiter.tryAcquire("music-play:" + k[0], limit <= 0 ? 0 : limit * 2)) {
                    return playJson(HttpStatus.TOO_MANY_REQUESTS, playBody(id, PlayService.rateLimited(null), false));
                }
                return plays.resolve(id, level, fresh).flatMap(p -> {
                    if (p.ok() && !asJson) {
                        // 音频地址二十分钟左右就会过期，不能让浏览器缓存这次跳转
                        return ServerResponse.status(HttpStatus.FOUND).location(URI.create(p.url()))
                            .header("Cache-Control", MusicPageRouter.NO_STORE)
                            .header("X-Robots-Tag", "noindex")
                            .build();
                    }
                    return playJson(statusOf(p), playBody(id, p, asJson));
                });
            }));
    }

    static HttpStatus statusOf(PlayService.PlayResult p) {
        return switch (p.status()) {
            case "ok" -> HttpStatus.OK;
            case "unavailable" -> HttpStatus.NOT_FOUND;
            case "rate_limited" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    static Map<String, Object> playBody(String id, PlayService.PlayResult p, boolean withUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", p.status());
        if (withUrl && p.url() != null) {
            body.put("url", p.url());
        }
        if (p.trial()) {
            Map<String, Object> trial = new LinkedHashMap<>();
            trial.put("start", p.trialStart());
            trial.put("end", p.trialEnd());
            body.put("trial", trial);
        }
        body.put("level", p.level());
        body.put("br", p.br());
        body.put("identity", p.identity());
        body.put("message", p.message());
        return body;
    }

    static Mono<ServerResponse> playJson(HttpStatus status, Map<String, Object> body) {
        return MusicPageRouter.json(status, body, MusicPageRouter.NO_STORE);
    }

    Mono<ServerResponse> lyric(ServerRequest r) {
        String id = r.pathVariable("id");
        return settings.advanced()
            .flatMap(adv -> {
                String ip = NcmGateway.clientIp(r, adv);
                if (!limiter.tryAcquire("music-lyric:" + ip, adv.publicLimit())) {
                    return MusicPageRouter.json(HttpStatus.TOO_MANY_REQUESTS, empty(id), MusicPageRouter.NO_STORE);
                }
                return finder.lyric(id)
                    .flatMap(v -> MusicPageRouter.json(HttpStatus.OK, lyricJson(id, v), LYRIC_CACHE))
                    .switchIfEmpty(Mono.defer(() ->
                        MusicPageRouter.json(HttpStatus.OK, empty(id), MusicPageRouter.NO_STORE)))
                    // 网易云出错或超时：明确返回 503，不缓存，下次再试
                    .onErrorResume(NcmUnavailableException.class, e -> {
                        Map<String, Object> body = empty(id);
                        body.put("error", "unavailable");
                        return MusicPageRouter.json(HttpStatus.SERVICE_UNAVAILABLE, body, MusicPageRouter.NO_STORE);
                    });
            });
    }

    static Map<String, Object> lyricJson(String id, LyricVo v) {
        List<Object> lines = new ArrayList<>();
        if (v.getLines() != null) {
            for (LyricLineVo l : v.getLines()) {
                if (l.getTimeMs() == null || l.getText() == null) {
                    continue;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("t", l.getTimeMs());
                line.put("text", l.getText());
                line.put("tr", l.getTranslation());
                lines.add(line);
                if (lines.size() >= MAX_LINES) {
                    break;
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("instrumental", Boolean.TRUE.equals(v.getInstrumental()));
        body.put("lines", lines);
        return body;
    }

    static Map<String, Object> empty(String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("instrumental", false);
        body.put("lines", List.of());
        return body;
    }
}

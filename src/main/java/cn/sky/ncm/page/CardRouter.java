package cn.sky.ncm.page;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.finder.NcmUnavailableException;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.gateway.NcmGateway;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 文章插歌卡片的数据接口（页面路由，不在 /apis 下，主题和访客都能直接请求）。
 * <pre>
 * GET /music/card/{type}/{id}    type：song / playlist / album
 *     200：{ type, id, title, sub, cover, path, total, tracks: [{ id, src, title, artist, cover, availability }] }
 *          Cache-Control: public, max-age=600
 *     404：不存在（error: "not_found"）
 *     429：请求太频繁（error: "rate_limited"）
 *     503：网易云出错或超时（error: "unavailable"），不缓存，下次再试
 * </pre>
 * 值为空的字段不输出（例如没有封面时没有 cover；/music 页面关闭时没有 path）。
 * 歌单、专辑最多带 {@link #MAX_TRACKS} 首；total 是歌单 / 专辑的总歌曲数。
 * 按访客 IP 限流，额度和「高级 → 公开接口每分钟请求数」相同，和 /music 页面的开关无关。
 */
@Component
public class CardRouter {

    static final int MAX_TRACKS = 50;
    static final String CARD_CACHE = "public, max-age=600";
    static final int COVER_SIZE = 200;
    static final int TRACK_COVER_SIZE = 160;

    private final SettingService settings;
    private final NcmFinderImpl finder;
    private final RateLimiter limiter;

    public CardRouter(SettingService settings, NcmFinderImpl finder, RateLimiter limiter) {
        this.settings = settings;
        // 严格模式：网易云出错时抛 NcmUnavailableException，用来区分“出错”（503）和“不存在”（404）
        this.finder = finder.strict();
        this.limiter = limiter;
    }

    @Bean
    RouterFunction<ServerResponse> ncmCardRouter() {
        return route(GET("/music/card/{type:song|playlist|album}/" + MusicPageRouter.ID_PATTERN), this::card);
    }

    Mono<ServerResponse> card(ServerRequest r) {
        String type = r.pathVariable("type");
        String id = r.pathVariable("id");
        return settings.advanced().flatMap(adv -> {
            String ip = NcmGateway.clientIp(r, adv);
            if (!limiter.tryAcquire("music-card:" + ip, adv.publicLimit())) {
                return MusicPageRouter.json(HttpStatus.TOO_MANY_REQUESTS, error(type, id, "rate_limited"),
                    MusicPageRouter.NO_STORE);
            }
            return pageOpen().flatMap(withPath -> load(type, id, withPath))
                .flatMap(body -> MusicPageRouter.json(HttpStatus.OK, body, CARD_CACHE))
                .switchIfEmpty(Mono.defer(() -> MusicPageRouter.json(HttpStatus.NOT_FOUND,
                    error(type, id, "not_found"), MusicPageRouter.NO_STORE)))
                // 网易云出错或超时：明确返回 503，不缓存
                .onErrorResume(NcmUnavailableException.class, e -> MusicPageRouter.json(
                    HttpStatus.SERVICE_UNAVAILABLE, error(type, id, "unavailable"), MusicPageRouter.NO_STORE));
        });
    }

    /** /music 页面是否开着：关着时不给站内路径，卡片标题改为链到网易云。读取设置失败时按关闭处理。 */
    Mono<Boolean> pageOpen() {
        return settings.page().map(PageSetting::open).defaultIfEmpty(false).onErrorReturn(false);
    }

    Mono<Map<String, Object>> load(String type, String id, boolean withPath) {
        BiFunction<String, Integer, String> thumb = finder::thumbnail;
        return switch (type) {
            case "song" -> finder.song(id).map(s -> songCard(s, withPath, thumb));
            case "playlist" -> finder.playlist(id, MAX_TRACKS).map(p -> playlistCard(p, withPath, thumb));
            case "album" -> finder.album(id).map(a -> albumCard(a, withPath, thumb));
            default -> Mono.empty();
        };
    }

    // ---------- JSON 组装（静态方法，方便测试） ----------

    static Map<String, Object> songCard(SongVo s, boolean withPath, BiFunction<String, Integer, String> thumb) {
        String sub = joinNonBlank(" · ", s.getArtistNames(), s.getAlbum() == null ? null : s.getAlbum().getName());
        List<Object> tracks = new ArrayList<>();
        Map<String, Object> t = track(s, thumb);
        if (t != null) {
            tracks.add(t);
        }
        return card("song", s.getId(), s.getName(), sub, cover(s.getCoverUrl(), thumb), withPath, null, tracks);
    }

    static Map<String, Object> playlistCard(PlaylistVo p, boolean withPath,
                                            BiFunction<String, Integer, String> thumb) {
        Integer total = p.getTrackCount();
        String count = total == null ? null : total + " 首";
        String creator = p.getCreator() == null ? null : p.getCreator().getNickname();
        String sub = joinNonBlank(" · ", creator, count);
        return card("playlist", p.getId(), p.getName(), sub, cover(p.getCoverUrl(), thumb), withPath, total,
            tracks(p.getTracks(), thumb));
    }

    static Map<String, Object> albumCard(AlbumVo a, boolean withPath, BiFunction<String, Integer, String> thumb) {
        Integer total = a.getSize() != null ? a.getSize() : (a.getTracks() == null ? null : a.getTracks().size());
        String sub = joinNonBlank(" · ", a.getArtistNames(), a.getPublishDate());
        return card("album", a.getId(), a.getName(), sub, cover(a.getCoverUrl(), thumb), withPath, total,
            tracks(a.getTracks(), thumb));
    }

    static Map<String, Object> card(String type, String id, String title, String sub, String cover,
                                    boolean withPath, Integer total, List<Object> tracks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("id", id);
        body.put("title", title == null ? "" : title);
        body.put("sub", sub);
        body.put("cover", cover);
        body.put("path", withPath && id != null ? "/music/" + type + "/" + id : null);
        body.put("total", total);
        body.put("tracks", tracks);
        return body;
    }

    static List<Object> tracks(List<SongVo> songs, BiFunction<String, Integer, String> thumb) {
        List<Object> list = new ArrayList<>();
        if (songs == null) {
            return list;
        }
        for (SongVo s : songs) {
            Map<String, Object> t = track(s, thumb);
            if (t == null) {
                continue;
            }
            list.add(t);
            if (list.size() >= MAX_TRACKS) {
                break;
            }
        }
        return list;
    }

    /** 播放器的 track：{ id, src, title, artist, cover, availability }。没有 ID 或播放地址的歌跳过。 */
    static Map<String, Object> track(SongVo s, BiFunction<String, Integer, String> thumb) {
        if (s == null || s.getId() == null || s.getPlayUrl() == null || s.getPlayUrl().isEmpty()) {
            return null;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", s.getId());
        t.put("src", s.getPlayUrl());
        t.put("title", s.getName() == null ? "" : s.getName());
        t.put("artist", s.getArtistNames() == null ? "" : s.getArtistNames());
        t.put("cover", cover(s.getCoverUrl(), thumb, TRACK_COVER_SIZE));
        t.put("availability", s.getAvailability());
        return t;
    }

    static String cover(String url, BiFunction<String, Integer, String> thumb) {
        return cover(url, thumb, COVER_SIZE);
    }

    static String cover(String url, BiFunction<String, Integer, String> thumb, int size) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = thumb.apply(url, size);
        return u == null || u.isEmpty() ? null : u;
    }

    static String joinNonBlank(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(sep);
            }
            b.append(p.strip());
        }
        return b.isEmpty() ? null : b.toString();
    }

    static Map<String, Object> error(String type, String id, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("id", id);
        body.put("error", error);
        return body;
    }
}

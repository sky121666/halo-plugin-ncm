package cn.sky.ncm.page;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.featured.FeaturedService.HomePlaylists;
import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.finder.NcmUnavailableException;
import cn.sky.ncm.finder.SearchType;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.PlaylistSummaryVo;
import cn.sky.ncm.finder.vo.PlayRecordVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.SuggestGroupVo;
import cn.sky.ncm.finder.vo.SuggestItemVo;
import cn.sky.ncm.gateway.NcmGateway;
import cn.sky.ncm.security.ClientIp;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.PageSetting;
import cn.sky.ncm.setting.SettingService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;

/** Folia 的页面入口和同源音乐目录接口。 */
@Slf4j
@Component
public class MusicPageRouter {

    public static final String ID_PATTERN = "{id:\\d+}";
    static final int SEARCH_PAGE_SIZE = 30;
    private static final Set<String> HOME_SECTIONS = Set.of("playlists", "profile", "recent", "rank", "daily");

    private final SettingService settings;
    /** 严格模式：网易云出错时抛 NcmUnavailableException，页面据此显示“服务暂时不可用”而不是“没有内容”。 */
    private final NcmFinderImpl finder;
    private final RateLimiter limiter;
    /** 后台「歌单发布」 */
    private final FeaturedService featured;
    private final String assetsVersion;
    private final FoliaPage folia = new FoliaPage();

    public MusicPageRouter(SettingService settings, NcmFinderImpl finder,
                           RateLimiter limiter, PluginContext pluginContext, FeaturedService featured) {
        this.featured = featured;
        this.settings = settings;
        this.finder = finder.strict();
        this.limiter = limiter;
        this.assetsVersion = Objects.requireNonNullElse(pluginContext.getVersion(), "0");
    }

    @Bean
    RouterFunction<ServerResponse> ncmMusicPageRouter() {
        return route(GET("/music"), this::home)
            .andRoute(GET("/music/explore/{feed}"), this::explore)
            .andRoute(GET("/music/search"), this::search)
            .andRoute(GET("/music/search/suggest"), this::suggest)
            .andRoute(GET("/music/library"), this::library)
            .andRoute(GET("/music/playlist/" + ID_PATTERN), this::playlist)
            .andRoute(GET("/music/album/" + ID_PATTERN), this::album)
            .andRoute(GET("/music/artist/" + ID_PATTERN), this::artist)
            .andRoute(GET("/music/song/" + ID_PATTERN), this::song);
    }

    // ---------- 页面 ----------

    Mono<ServerResponse> explore(ServerRequest r) {
        String feed = r.pathVariable("feed");
        int offset = queryInteger(r, "offset", 0, 0, 20000);
        int limit = queryInteger(r, "limit", 12, 1, 40);
        String cursor = r.queryParam("cursor").orElse("");
        if (cursor.length() > 4096) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "invalid-cursor"), NO_STORE);
        }
        return opened().then(settings.advanced()).flatMap(advanced -> {
            String ip = ClientIp.resolve(r, advanced);
            if (!limiter.tryAcquire("music-explore:" + ip, advanced.publicLimit())) {
                return json(HttpStatus.TOO_MANY_REQUESTS, Map.of("error", "rate-limited"), NO_STORE);
            }
            Mono<Map<String, Object>> result = "home".equals(feed) ? finder.foliaGuestHome(cursor)
                : finder.foliaDiscovery(feed, offset, limit, r.queryParam("id").orElse(""));
            return result.switchIfEmpty(notFound()).flatMap(body -> json(HttpStatus.OK, body, NO_STORE));
        }).onErrorResume(NcmUnavailableException.class, error ->
            json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "unavailable"), NO_STORE));
    }

    Mono<ServerResponse> home(ServerRequest r) {
        return opened().flatMap(s -> {
            String part = r.queryParam("section").filter(HOME_SECTIONS::contains).orElse("");
            if (!asJson(r)) {
                return settings.player().flatMap(player -> folia.render(s, player, false, assetsVersion));
            }
            Set<String> failed = ConcurrentHashMap.newKeySet();
            Mono<HomePlaylists> published = part.isEmpty() || "playlists".equals(part)
                ? featured.homePlaylists(!part.isEmpty()) : Mono.just(new HomePlaylists(List.of(), false));
            return Mono.zip(homeSection(part, "profile", () -> finder.siteProfile().flux(), failed), published,
                    homeSection(part, "playlists", () -> finder.sitePlaylists(24), failed),
                    homeSection(part, "recent", () -> finder.siteRecentSongs(10), failed),
                    homeSection(part, "rank", () -> finder.siteWeekRank(10), failed),
                    homeSection(part, "daily", () -> finder.siteDailyRecommend(10), failed))
                .flatMap(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (t.getT2().unavailable()) {
                        failed.add("playlists");
                    }
                    List<PlaylistVo> featuredPlaylists = t.getT2().playlists();
                    PlaylistVo lead = leadPlaylist(featuredPlaylists, t.getT3());
                    Set<String> featuredIds = new HashSet<>();
                    featuredPlaylists.forEach(p -> featuredIds.add(p.getId()));
                    if (lead != null) {
                        featuredIds.add(lead.getId());
                    }
                    m.put("leadPlaylist", lead);
                    m.put("profile", t.getT1().isEmpty() ? null : t.getT1().getFirst());
                    m.put("featuredPlaylists", featuredPlaylists);
                    m.put("publicPlaylistShelf", t.getT3().stream()
                        .filter(p -> !featuredIds.contains(p.getId())).toList());
                    m.put("recentSongs", t.getT4().stream().map(PlayRecordVo::getSong).toList());
                    m.put("weekRank", t.getT5().stream().map(PlayRecordVo::getSong).toList());
                    m.put("dailySongs", t.getT6());
                    m.put("homeErrors", List.copyOf(failed));
                    return json(HttpStatus.OK, m, NO_STORE);
                });
        });
    }

    private static <T> Mono<List<T>> homeSection(String part, String name, Supplier<Flux<T>> source,
                                                Set<String> failed) {
        if (!part.isEmpty() && !name.equals(part)) {
            return Mono.just(List.of());
        }
        return Flux.defer(source).collectList().onErrorResume(NcmUnavailableException.class, e -> {
            failed.add(name);
            return Mono.just(List.of());
        });
    }

    private static PlaylistVo leadPlaylist(List<PlaylistVo> featured, List<PlaylistSummaryVo> playlists) {
        if (!featured.isEmpty()) {
            return featured.getFirst();
        }
        if (playlists.isEmpty()) {
            return null;
        }
        PlaylistSummaryVo first = playlists.getFirst();
        return PlaylistVo.builder().id(first.getId()).name(first.getName()).coverUrl(first.getCoverUrl())
            .trackCount(first.getTrackCount()).build();
    }

    Mono<ServerResponse> playlist(ServerRequest r) {
        String id = r.pathVariable("id");
        return opened().flatMap(s -> finder.playlist(id, 0)
            .switchIfEmpty(notFound())
            .flatMap(found -> featured.decorate(id, found))
            .flatMap(pl -> {
                if (!asJson(r)) {
                    Map<String, Object> schema = new LinkedHashMap<>();
                    schema.put("numTracks", pl.getTrackCount());
                    return showDetail(s, new FoliaPage.Detail("playlist", r.path(), pl.getName(),
                        pl.getDescription(), pl.getCoverUrl(), pl.getUrl(), withTracks(pl, List.of()), schema));
                }
                int limit = queryInteger(r, "limit", s.size(), 0, 200);
                int offset = queryInteger(r, "offset", 0, 0, 20000);
                return (limit == 0 ? Mono.just(List.<SongVo>of())
                    : finder.playlistTracks(id, offset, limit).collectList())
                    .flatMap(tracks -> json(HttpStatus.OK, Map.of("playlist", withTracks(pl, tracks)), NO_STORE));
            })
            .onErrorResume(NcmUnavailableException.class, e -> unavailable(r, s, e)));
    }

    Mono<ServerResponse> album(ServerRequest r) {
        return opened().flatMap(s -> finder.album(r.pathVariable("id"))
            .switchIfEmpty(notFound())
            .flatMap(album -> {
                var item = FoliaPage.album(album);
                if (asJson(r)) {
                    return json(HttpStatus.OK, Map.of("album", item), NO_STORE);
                }
                item.remove("tracks");
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("datePublished", album.getPublishDate());
                schema.put("numTracks", album.getSize());
                schema.put("byArtist", named("MusicGroup", album.getArtistNames()));
                return showDetail(s, new FoliaPage.Detail("album", r.path(), album.getName(),
                    album.getDescription(), album.getCoverUrl(), album.getUrl(), item, schema));
            })
            .onErrorResume(NcmUnavailableException.class, e -> unavailable(r, s, e)));
    }

    Mono<ServerResponse> artist(ServerRequest r) {
        return opened().flatMap(s -> finder.artist(r.pathVariable("id"), 50)
            .switchIfEmpty(notFound())
            .flatMap(artist -> {
                if (asJson(r)) {
                    return json(HttpStatus.OK, Map.of("artist", artist), NO_STORE);
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", artist.getId());
                item.put("name", artist.getName());
                item.put("aliases", artist.getAliases());
                item.put("coverUrl", artist.getCoverUrl());
                item.put("briefDesc", artist.getBriefDesc());
                item.put("musicSize", artist.getMusicSize());
                item.put("albumSize", artist.getAlbumSize());
                return showDetail(s, new FoliaPage.Detail("artist", r.path(), artist.getName(),
                    artist.getBriefDesc(), artist.getCoverUrl(), artist.getUrl(), item, Map.of()));
            })
            .onErrorResume(NcmUnavailableException.class, e -> unavailable(r, s, e)));
    }

    Mono<ServerResponse> song(ServerRequest r) {
        return opened().flatMap(s -> finder.song(r.pathVariable("id"))
            .switchIfEmpty(notFound())
            .flatMap(song -> {
                if (asJson(r)) {
                    return json(HttpStatus.OK, Map.of("song", song), NO_STORE);
                }
                Map<String, Object> schema = new LinkedHashMap<>();
                if (song.getDurationMs() != null && song.getDurationMs() > 0) {
                    schema.put("duration", "PT" + song.getDurationMs() / 1000 + "S");
                }
                schema.put("byArtist", named("MusicGroup", song.getArtistNames()));
                if (song.getAlbum() != null) {
                    schema.put("inAlbum", named("MusicAlbum", song.getAlbum().getName()));
                }
                return showDetail(s, new FoliaPage.Detail("song", r.path(), song.getName(),
                    song.getArtistNames(), song.getCoverUrl(), song.getUrl(), song, schema));
            })
            .onErrorResume(NcmUnavailableException.class, e -> unavailable(r, s, e)));
    }

    private static Map<String, String> named(String type, String name) {
        return name == null || name.isBlank() ? null : Map.of("@type", type, "name", name);
    }

    private Mono<ServerResponse> showDetail(PageSetting page, FoliaPage.Detail entry) {
        return settings.player().flatMap(player ->
            folia.render(page, player, false, entry, HttpStatus.OK, assetsVersion));
    }

    Mono<ServerResponse> library(ServerRequest r) {
        var title = "个人曲库";
        var entry = new FoliaPage.Detail("library", r.path(), title, null, null, null,
            Map.of("name", title), Map.of());
        return opened().flatMap(page -> showDetail(page, entry));
    }

    Mono<ServerResponse> search(ServerRequest r) {
        String raw = r.queryParam("q").orElse("");
        String keyword = NcmFinderImpl.normalizeKeyword(raw);
        SearchType type = SearchType.of(r.queryParam("type").orElse(null));
        int page = queryInteger(r, "page", 1, 1, Integer.MAX_VALUE);
        return settings.page().filter(PageSetting::searchOpen).switchIfEmpty(notFound()).flatMap(s -> {
            if (!asJson(r)) {
                return settings.player().flatMap(player -> folia.render(s, player, true, assetsVersion));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rateLimited", false);
            m.put("serviceError", false);
            m.put("hotError", false);
            m.put("hotKeywords", List.of());
            m.put("tooLong", keyword == null && !raw.isBlank());
            if (keyword == null) {
                if (!raw.isBlank()) {
                    return searchResponse(m, HttpStatus.BAD_REQUEST);
                }
                return finder.hotKeywords(10).collectList().flatMap(items -> {
                    m.put("hotKeywords", items);
                    return searchResponse(m, HttpStatus.OK);
                }).onErrorResume(NcmUnavailableException.class, e -> {
                    m.put("hotError", true);
                    return searchResponse(m, HttpStatus.SERVICE_UNAVAILABLE);
                });
            }
            return settings.advanced().flatMap(adv -> {
                String ip = NcmGateway.clientIp(r, adv);
                if (!limiter.tryAcquire("music-search:" + ip, s.searchLimit())) {
                    m.put("rateLimited", true);
                    m.put("hotKeywords", List.of());
                    return searchResponse(m, HttpStatus.TOO_MANY_REQUESTS);
                }
                return finder.search(keyword, type.key(), page, SEARCH_PAGE_SIZE).flatMap(res -> {
                    long total = Math.min(res.getTotal(), NcmFinderImpl.MAX_SEARCH_RESULTS);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("items", switch (type) {
                        case SONG -> res.getSongs();
                        case PLAYLIST -> res.getPlaylists();
                        case ALBUM -> res.getAlbums().stream().map(FoliaPage::album).toList();
                        case ARTIST -> res.getArtists();
                    });
                    result.put("total", total);
                    result.put("page", res.getPage());
                    return json(HttpStatus.OK, result, NO_STORE);
                }).onErrorResume(NcmUnavailableException.class, e -> {
                    m.put("serviceError", true);
                    m.put("hotKeywords", List.of());
                    return searchResponse(m, HttpStatus.SERVICE_UNAVAILABLE);
                });
            });
        });
    }

    private Mono<ServerResponse> searchResponse(Map<String, Object> data, HttpStatus status) {
        return json(status, data, NO_STORE);
    }

    /**
     * 搜索联想 JSON：{ "keyword": "...", "groups": [{ "type", "label", "items": [{ "type", "id", "name", "sub",
     * "cover", "path" }] }] }。关闭时 404，限流时 429，关键词无效时返回空 groups。
     */
    Mono<ServerResponse> suggest(ServerRequest r) {
        String keyword = NcmFinderImpl.normalizeKeyword(r.queryParam("q").orElse(""));
        return settings.page().filter(PageSetting::suggestOpen).switchIfEmpty(notFound()).flatMap(s -> {
            if (keyword == null) {
                return json(HttpStatus.OK, suggestJson(null, List.of()), NO_STORE);
            }
            return settings.advanced().flatMap(adv -> {
                String ip = NcmGateway.clientIp(r, adv);
                if (!limiter.tryAcquire("music-suggest:" + ip, s.suggestLimit())) {
                    return json(HttpStatus.TOO_MANY_REQUESTS, suggestJson(keyword, List.of()), NO_STORE);
                }
                return finder.suggest(keyword)
                    .map(v -> suggestJson(keyword, v.getGroups()))
                    .defaultIfEmpty(suggestJson(keyword, List.of()))
                    .flatMap(body -> json(HttpStatus.OK, body, "private, max-age=300"))
                    .onErrorResume(NcmUnavailableException.class, e -> {
                        Map<String, Object> body = suggestJson(keyword, List.of());
                        body.put("error", "unavailable");
                        return json(HttpStatus.SERVICE_UNAVAILABLE, body, NO_STORE);
                    });
            });
        });
    }

    Map<String, Object> suggestJson(String keyword, List<SuggestGroupVo> groups) {
        List<Object> gs = new ArrayList<>();
        for (SuggestGroupVo g : groups) {
            List<Object> items = new ArrayList<>();
            for (SuggestItemVo i : g.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", i.getType());
                item.put("id", i.getId());
                item.put("name", i.getName());
                item.put("sub", i.getSub());
                item.put("cover", i.getCoverUrl() == null ? null : finderThumbnail(i.getCoverUrl()));
                item.put("path", i.getPath());
                items.add(item);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("type", g.getType());
            group.put("label", g.getLabel());
            group.put("items", items);
            gs.add(group);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", keyword == null ? "" : keyword);
        body.put("groups", gs);
        return body;
    }

    static final String NO_STORE = "no-store";

    private static boolean asJson(ServerRequest request) {
        return request.queryParam("format").filter("json"::equals).isPresent();
    }

    private static int queryInteger(ServerRequest request, String name, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(request.queryParam(name).orElse(String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 自己序列化成字符串（Halo 2.27 默认用 Jackson 3 编码）。 */
    static Mono<ServerResponse> json(HttpStatus status, Map<String, Object> body, String cacheControl) {
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", cacheControl)
            .header("X-Robots-Tag", "noindex")
            .bodyValue(Js.json(body));
    }

    String finderThumbnail(String url) {
        return finder.thumbnail(url, 80);
    }

    /** 页面关闭（或设置读取失败）时 404。 */
    Mono<PageSetting> opened() {
        return settings.page().filter(PageSetting::open).switchIfEmpty(notFound());
    }

    Mono<ServerResponse> unavailable(ServerRequest r, PageSetting page, NcmUnavailableException e) {
        log.warn("[ncm] 音乐页面 {} 加载失败（{}）: {}", r.path(), e.api(), e.getMessage());
        if (asJson(r)) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "unavailable"), NO_STORE);
        }
        String title = "音乐服务暂时不可用";
        var item = Map.of("name", title, "message", "暂时连不上网易云音乐，请稍后重试。");
        var entry = new FoliaPage.Detail("error", r.path(), title, null, null, null, item, Map.of());
        return settings.player().flatMap(player ->
            folia.render(page, player, false, entry, HttpStatus.SERVICE_UNAVAILABLE, assetsVersion));
    }

    static <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    static PlaylistVo withTracks(PlaylistVo pl, List<SongVo> tracks) {
        return PlaylistVo.builder()
            .id(pl.getId())
            .name(pl.getName())
            .coverUrl(pl.getCoverUrl())
            .description(pl.getDescription())
            .tags(pl.getTags())
            .trackCount(pl.getTrackCount())
            .playCount(pl.getPlayCount())
            .creator(pl.getCreator())
            .tracks(tracks)
            .url(pl.getUrl())
            .build();
    }

}

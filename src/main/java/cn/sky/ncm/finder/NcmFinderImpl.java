package cn.sky.ncm.finder;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.finder.FinderSource.As;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.ArtistVo;
import cn.sky.ncm.finder.vo.HotKeywordVo;
import cn.sky.ncm.finder.vo.LyricVo;
import cn.sky.ncm.finder.vo.PlayRecordVo;
import cn.sky.ncm.finder.vo.PlaylistSummaryVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SearchResultVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.SuggestVo;
import cn.sky.ncm.finder.vo.UserVo;
import cn.sky.ncm.setting.FinderSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.theme.finders.Finder;

@Finder("ncmFinder")
public class NcmFinderImpl implements NcmFinder {

    static final Pattern ID = Pattern.compile("^\\d{1,20}$");
    static final int MAX_LIMIT = 200;

    private final FinderSource source;
    private final SettingService settings;
    private final AccountService accounts;
    private final boolean strict;

    @Autowired
    public NcmFinderImpl(FinderSource source, SettingService settings, AccountService accounts) {
        this(source, settings, accounts, false);
    }

    private NcmFinderImpl(FinderSource source, SettingService settings, AccountService accounts, boolean strict) {
        this.source = source;
        this.settings = settings;
        this.accounts = accounts;
        this.strict = strict;
    }

    /**
     * 同样的查询，但网易云出错、超时时抛 {@link NcmUnavailableException} 而不是返回空。
     * 给插件自己的页面用，以便区分“服务暂时不可用”和“没有内容”；主题里的 ncmFinder 仍然是宽松的。
     */
    public NcmFinderImpl strict() {
        return strict ? this : new NcmFinderImpl(source, settings, accounts, true);
    }

    Mono<JsonNode> call(String api, Map<String, String> query, As as) {
        return strict ? source.fetch(api, query, as) : source.call(api, query, as);
    }

    // ---------- 公开数据 ----------

    public Mono<Map<String, Object>> foliaDiscovery(String feed, int offset, int limit, String seed) {
        Map<String, String> page = Map.of("limit", String.valueOf(limit), "offset", String.valueOf(offset));
        return switch (feed) {
            case "charts" -> call("toplist", Map.of(), As.ANONYMOUS)
                .map(body -> Map.of("list", FoliaFeedPayload.collections(body.path("list"))));
            case "new-songs" -> call("top_song", Map.of(), As.ANONYMOUS)
                .map(body -> Map.of("songs", FoliaFeedPayload.songs(body, "data", "songs")));
            case "new-albums" -> call("album_new", page, As.ANONYMOUS)
                .map(body -> Map.of("albums", FoliaFeedPayload.collections(body.path("albums")),
                    "total", body.path("total").asInt(0)));
            case "hot-playlists" -> call("top_playlist", page, As.ANONYMOUS)
                .map(body -> Map.of("playlists", FoliaFeedPayload.collections(body.path("playlists")),
                    "more", body.path("more").asBoolean(false)));
            case "similar-songs" -> validId(seed) ? call("simi_song", Map.of("id", seed), As.ANONYMOUS)
                .map(body -> Map.of("songs", FoliaFeedPayload.songs(body, "songs"))) : Mono.empty();
            default -> Mono.empty();
        };
    }

    public Mono<Map<String, Object>> foliaGuestHome(String cursor) {
        return call("homepage_block_page", foliaHomeParams(cursor), As.ANONYMOUS).map(FoliaFeedPayload::home);
    }

    public static Map<String, String> foliaHomeParams(String cursor) {
        return cursor.isEmpty() ? Map.of("refresh", "true") : Map.of("cursor", cursor);
    }

    @Override
    public Mono<SongVo> song(String id) {
        if (!validId(id)) {
            return Mono.empty();
        }
        return call("song_detail", Map.of("ids", id.trim()), As.PUBLIC)
            .flatMap(b -> Mono.justOrEmpty(NcmVoMapper.songs(b).stream().findFirst()));
    }

    @Override
    public Flux<SongVo> songs(String ids) {
        List<String> list = ids == null ? List.of() : Arrays.stream(ids.split(","))
            .map(String::trim).filter(NcmFinderImpl::validId).distinct().limit(MAX_LIMIT).toList();
        if (list.isEmpty()) {
            return Flux.empty();
        }
        return call("song_detail", Map.of("ids", String.join(",", list)), As.PUBLIC)
            .flatMapIterable(b -> {
                Map<String, SongVo> byId = new LinkedHashMap<>();
                NcmVoMapper.songs(b).forEach(s -> byId.put(s.getId(), s));
                List<SongVo> ordered = new ArrayList<>();
                list.forEach(i -> {
                    if (byId.containsKey(i)) {
                        ordered.add(byId.get(i));
                    }
                });
                return ordered;
            });
    }

    @Override
    public Mono<PlaylistVo> playlist(String id) {
        return playlist(id, 50);
    }

    @Override
    public Mono<PlaylistVo> playlist(String id, Integer limit) {
        if (!validId(id)) {
            return Mono.empty();
        }
        String pid = id.trim();
        int n = limit(limit, 50);
        Mono<JsonNode> detail = call("playlist_detail", Map.of("id", pid), As.PUBLIC)
            .filter(NcmVoMapper::hasPlaylist);
        Mono<List<SongVo>> tracks = n == 0 ? Mono.just(List.of())
            : call("playlist_track_all", Map.of("id", pid, "limit", String.valueOf(n), "offset", "0"), As.PUBLIC)
                .map(NcmVoMapper::songs)
                .defaultIfEmpty(List.of());
        return Mono.zip(detail, tracks).map(t -> NcmVoMapper.playlist(t.getT1(), t.getT2()));
    }

    @Override
    public Flux<SongVo> playlistTracks(String id, Integer offset, Integer limit) {
        if (!validId(id)) {
            return Flux.empty();
        }
        int n = limit(limit, 50);
        int from = offset == null ? 0 : Math.max(0, offset);
        if (n == 0) {
            return Flux.empty();
        }
        return call("playlist_track_all",
                Map.of("id", id.trim(), "limit", String.valueOf(n), "offset", String.valueOf(from)), As.PUBLIC)
            .flatMapIterable(NcmVoMapper::songs);
    }

    @Override
    public Mono<SearchResultVo> search(String keyword, String type, Integer page, Integer size) {
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            return Mono.empty();
        }
        SearchType t = SearchType.of(type);
        int n = size == null ? 30 : Math.max(1, Math.min(size, 100));
        int p = Math.max(1, Math.min(page == null ? 1 : page, maxSearchPage(n)));
        return call("cloudsearch", Map.of("keywords", kw, "type", String.valueOf(t.code()),
                "limit", String.valueOf(n), "offset", String.valueOf((p - 1) * n)), As.PUBLIC)
            .filter(b -> b.path("result").isObject())
            .map(b -> NcmVoMapper.search(b, kw, t, p, n))
            .defaultIfEmpty(NcmVoMapper.emptySearch(kw, t, p, n));
    }

    @Override
    public Mono<SuggestVo> suggest(String keyword) {
        String kw = normalizeKeyword(keyword);
        if (kw == null) {
            return Mono.empty();
        }
        return call("search_suggest", Map.of("keywords", kw), As.PUBLIC)
            .map(b -> NcmVoMapper.suggest(b, kw, 5))
            .defaultIfEmpty(SuggestVo.builder().keyword(kw).groups(List.of()).build());
    }

    @Override
    public Flux<HotKeywordVo> hotKeywords(Integer limit) {
        int n = limit == null ? 10 : Math.max(0, Math.min(limit, 30));
        if (n == 0) {
            return Flux.empty();
        }
        return call("search_hot_detail", Map.of(), As.PUBLIC)
            .flatMapIterable(b -> NcmVoMapper.hotKeywords(b, n));
    }

    /** 搜索最多返回到第 MAX_SEARCH_RESULTS 条。 */
    public static final int MAX_SEARCH_RESULTS = 1000;

    static int maxSearchPage(int size) {
        return Math.max(1, (MAX_SEARCH_RESULTS + size - 1) / size);
    }

    /** 去掉首尾空白和控制字符，连续空白合并为一个空格；为空或超过 100 个字符时返回 null。 */
    public static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String s = keyword.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return s.isEmpty() || s.codePointCount(0, s.length()) > 100 ? null : s;
    }

    @Override
    public Mono<AlbumVo> album(String id) {
        if (!validId(id)) {
            return Mono.empty();
        }
        return call("album", Map.of("id", id.trim()), As.PUBLIC)
            .filter(NcmVoMapper::hasAlbum)
            .map(NcmVoMapper::album);
    }

    @Override
    public Mono<ArtistVo> artist(String id) {
        return artist(id, 20);
    }

    @Override
    public Mono<ArtistVo> artist(String id, Integer limit) {
        if (!validId(id)) {
            return Mono.empty();
        }
        int n = limit(limit, 20);
        return call("artists", Map.of("id", id.trim()), As.PUBLIC)
            .filter(NcmVoMapper::hasArtist)
            .map(b -> NcmVoMapper.artist(b, n));
    }

    public Mono<Map<String, Object>> foliaArtist(String id, String section, int offset, int limit) {
        if (!validId(id)) {
            return Mono.empty();
        }
        String artistId = id.trim();
        Map<String, String> page = Map.of("id", artistId, "offset", String.valueOf(offset),
            "limit", String.valueOf(limit));
        return switch (section) {
            case "profile" -> call("artist_detail", Map.of("id", artistId), As.ANONYMOUS)
                .map(body -> body.path("data").path("artist"))
                .filter(artist -> artist.hasNonNull("id"))
                .map(artist -> Map.of("artist", NcmVoMapper.artistOf(artist, List.of())));
            case "songs" -> call("artist_songs", page, As.ANONYMOUS)
                .map(body -> artistPage("artist_songs", body, "songs", NcmVoMapper.songs(body), offset));
            case "albums" -> call("artist_album", page, As.ANONYMOUS)
                .map(body -> artistPage("artist_album", body, "hotAlbums",
                    FoliaFeedPayload.collections(body.path("hotAlbums")), offset));
            default -> Mono.empty();
        };
    }

    private static Map<String, Object> artistPage(String api, JsonNode body, String field, List<?> items, int offset) {
        JsonNode rows = body.path(field);
        if (!rows.isArray()) {
            throw new NcmUnavailableException(api, "网易云未返回歌手作品列表");
        }
        int nextOffset = offset + rows.size();
        int total = body.path("total").asInt(-1);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextOffset", nextOffset);
        page.put("hasMore", !rows.isEmpty() && body.path("more").asBoolean(nextOffset < total));
        if (total >= 0) {
            page.put("total", total);
        }
        return page;
    }

    @Override
    public Mono<LyricVo> lyric(String id) {
        if (!validId(id)) {
            return Mono.empty();
        }
        String sid = id.trim();
        return call("lyric_new", Map.of("id", sid), As.PUBLIC).map(b -> NcmVoMapper.lyric(sid, b));
    }

    public Mono<List<Map<String, Long>>> foliaChorus(String id) {
        if (!validId(id)) {
            return Mono.empty();
        }
        return call("song_chorus", Map.of("id", id.trim()), As.ANONYMOUS).map(NcmVoMapper::chorus);
    }

    @Override
    public String playUrl(String id) {
        return playUrl(id, "standard");
    }

    @Override
    public String playUrl(String id, String level) {
        return validId(id) ? NcmVoMapper.playUrl(id.trim(), level) : "";
    }

    @Override
    public String thumbnail(String url, Integer size) {
        return NcmVoMapper.thumbnail(url, size);
    }

    // ---------- 站长数据 ----------

    @Override
    public Mono<UserVo> siteProfile() {
        return gate(FinderSetting::profileOpen).flatMap(x -> siteUid())
            .flatMap(uid -> call("user_detail", Map.of("uid", uid), As.PUBLIC))
            .filter(NcmVoMapper::hasUser)
            .map(NcmVoMapper::user);
    }

    @Override
    public Flux<PlaylistSummaryVo> sitePlaylists() {
        return sitePlaylists(30);
    }

    @Override
    public Flux<PlaylistSummaryVo> sitePlaylists(Integer limit) {
        int n = limit(limit, 30);
        // 用公开身份请求：网易云只返回该用户公开的歌单
        return gate(FinderSetting::playlistsOpen).flatMap(x -> siteUid())
            .flatMap(uid -> call("user_playlist", Map.of("uid", uid, "limit", String.valueOf(n), "offset", "0"),
                As.PUBLIC).map(b -> NcmVoMapper.playlistSummaries(b, uid)))
            .flatMapIterable(Function.identity());
    }

    @Override
    public Flux<PlayRecordVo> siteRecentSongs() {
        return siteRecentSongs(20);
    }

    @Override
    public Flux<PlayRecordVo> siteRecentSongs(Integer limit) {
        int n = limit(limit, 20);
        return gate(FinderSetting::recentOpen)
            .flatMap(x -> call("record_recent_song", Map.of("limit", String.valueOf(Math.max(n, 1))), As.SITE))
            .flatMapIterable(b -> NcmVoMapper.recent(b, n));
    }

    @Override
    public Flux<PlayRecordVo> siteWeekRank() {
        return siteWeekRank(10);
    }

    @Override
    public Flux<PlayRecordVo> siteWeekRank(Integer limit) {
        return rank(true, limit(limit, 10));
    }

    @Override
    public Flux<PlayRecordVo> siteAllRank() {
        return siteAllRank(10);
    }

    @Override
    public Flux<PlayRecordVo> siteAllRank(Integer limit) {
        return rank(false, limit(limit, 10));
    }

    Flux<PlayRecordVo> rank(boolean week, int n) {
        return gate(FinderSetting::rankOpen).flatMap(x -> siteUid())
            .flatMap(uid -> call("user_record", Map.of("uid", uid, "type", week ? "1" : "0"), As.SITE))
            .flatMapIterable(b -> NcmVoMapper.rank(b, week, n));
    }

    @Override
    public Flux<SongVo> siteDailyRecommend() {
        return siteDailyRecommend(30);
    }

    @Override
    public Flux<SongVo> siteDailyRecommend(Integer limit) {
        int n = limit(limit, 30);
        return gate(FinderSetting::recommendOpen)
            .flatMap(x -> call("recommend_songs", Map.of(), As.SITE))
            .flatMapIterable(b -> NcmVoMapper.dailySongs(b, n));
    }

    // ---------- 工具 ----------

    /** 设置里对应开关打开时发出 true，否则为空；调用方用 flatMap 串联，关闭时不会再请求网易云。 */
    Mono<Boolean> gate(Predicate<FinderSetting> open) {
        return settings.finder().onErrorReturn(FinderSetting.CLOSED).map(open::test).filter(Boolean::booleanValue);
    }

    /** 站长账号的网易云 UID；没绑定时为空。 */
    Mono<String> siteUid() {
        return accounts.find(AccountService.SITE)
            .filter(a -> a.getStatus() != null && a.getStatus().getUserId() != null && a.getStatus().getUserId() > 0)
            .map(a -> String.valueOf(a.getStatus().getUserId()));
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id.trim()).matches();
    }

    static int limit(Integer limit, int def) {
        if (limit == null) {
            return def;
        }
        return Math.max(0, Math.min(limit, MAX_LIMIT));
    }
}

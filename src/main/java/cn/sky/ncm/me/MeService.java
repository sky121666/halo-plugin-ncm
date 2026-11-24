package cn.sky.ncm.me;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.NcmAccount;
import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.endpoint.dto.NcmMeLikeCheck;
import cn.sky.ncm.endpoint.dto.NcmMeLikeResult;
import cn.sky.ncm.endpoint.dto.NcmMePlaylist;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistList;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistRecord;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistTracks;
import cn.sky.ncm.endpoint.dto.NcmMeProfile;
import cn.sky.ncm.endpoint.dto.NcmMeRankRecords;
import cn.sky.ncm.endpoint.dto.NcmMeRecentRecords;
import cn.sky.ncm.endpoint.dto.NcmMeSong;
import cn.sky.ncm.endpoint.dto.NcmMeSongPage;
import cn.sky.ncm.endpoint.dto.NcmMeSubscriptionResult;
import cn.sky.ncm.endpoint.dto.NcmMeTracksResult;
import cn.sky.ncm.finder.FoliaFeedPayload;
import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.page.PlayService;
import cn.sky.ncm.page.PlayerRouter;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 个人音乐空间（个人中心 → 网抑云）的业务逻辑。
 * <ul>
 *   <li>只用当前 Halo 用户自己绑定的网易云账号；管理员本人没有单独绑定时，用他在后台绑定的站长账号
 *       （和 IdentityResolver 的规则一致）；其他情况一律 403，绝不借用站长账号</li>
 *   <li>接口名和参数都在这里写死，前端不能拼出任意网易云请求</li>
 *   <li>网易云返回错误时转成 ResponseStatusException：登录失效 403，其他业务错误 400，网络 / 服务端错误 502</li>
 * </ul>
 */
@Slf4j
@Component
public class MeService {

    static final String UNBOUND = "还没有绑定网易云账号，请先在「账号」标签页绑定";
    static final String UNREADABLE = "保存的网易云登录信息无法读取，请在「账号」标签页重新绑定";
    static final String EXPIRED = "网易云登录已失效，请在「账号」标签页重新绑定";
    static final String NOT_OWN = "只能修改自己创建的歌单";
    static final String LIKED_PLAYLIST = "「我喜欢的音乐」不能删除";
    static final String PLAYLIST_NOT_FOUND = "歌单不存在，或者没有权限查看";
    /** 最近播放每类最多条数（网易云接口本身最多 100）。 */
    static final int RECENT_LIMIT = 100;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final ApiRegistry registry;
    private final ApiExecutor executor;
    private final AccountService accounts;
    private final SettingService settings;
    private final LikeCache likeCache;
    private final PlayService plays;

    public MeService(ApiRegistry registry, ApiExecutor executor, AccountService accounts, SettingService settings,
                     LikeCache likeCache, PlayService plays) {
        this.registry = registry;
        this.executor = executor;
        this.accounts = accounts;
        this.settings = settings;
        this.likeCache = likeCache;
        this.plays = plays;
    }

    // ---------- 身份 ----------

    /** 当前用户的网易云身份；没有可用的账号时 403。 */
    public Mono<MeIdentity> identity(String username) {
        String name = AccountService.userAccountName(username);
        return accounts.find(name)
            .flatMap(a -> accounts.cookieOf(name)
                .filter(c -> !c.isEmpty())
                .map(c -> new MeIdentity(username, c, uidOf(a), false))
                .switchIfEmpty(Mono.error(() -> forbidden(UNREADABLE))))
            .switchIfEmpty(Mono.defer(() -> accounts.siteAccountIfOwnedBy(username)
                .flatMap(a -> accounts.siteCookieIfOwnedBy(username)
                    .filter(c -> !c.isEmpty())
                    .map(c -> new MeIdentity(username, c, uidOf(a), true))
                    .switchIfEmpty(Mono.error(() -> forbidden(UNREADABLE))))))
            .switchIfEmpty(Mono.error(() -> forbidden(UNBOUND)));
    }

    static String uidOf(NcmAccount a) {
        Long id = a == null || a.getStatus() == null ? null : a.getStatus().getUserId();
        return id == null || id <= 0 ? null : String.valueOf(id);
    }

    /** 绑定时没记下 UID 的旧数据：用 user_account 查一次。 */
    Mono<MeIdentity> withUid(MeIdentity id) {
        if (id.uid() != null) {
            return Mono.just(id);
        }
        return call("user_account", Map.of(), id, "获取账号信息")
            .map(body -> id.withUid(uidFromAccount(body)));
    }

    /** user_account 的返回：{ account: { id }, profile: { userId } }；Cookie 失效时两者都是 null。 */
    static String uidFromAccount(JsonNode body) {
        String uid = body.path("profile").path("userId").asText("");
        if (!MeParams.ID.matcher(uid).matches() || "0".equals(uid)) {
            uid = body.path("account").path("id").asText("");
        }
        if (!MeParams.ID.matcher(uid).matches() || "0".equals(uid)) {
            throw forbidden(EXPIRED);
        }
        return uid;
    }

    // ---------- 查询 ----------

    public Mono<NcmMeProfile> profile(MeIdentity id) {
        return call("user_account", Map.of(), id, "获取账号信息").flatMap(account -> {
            String uid = uidFromAccount(account);
            JsonNode p = account.path("profile");
            return call("user_detail", Map.of("uid", uid), id, "获取用户详情")
                .map(Optional::of)
                .onErrorResume(e -> Mono.just(Optional.<JsonNode>empty()))
                .map(detail -> {
                    JsonNode d = detail.orElse(null);
                    return NcmMeProfile.builder()
                        .userId(uid)
                        .nickname(p.path("nickname").asText(null))
                        .avatarUrl(MeMapper.https(p.path("avatarUrl").asText(null)))
                        .signature(blankToNull(p.path("signature").asText(
                            d == null ? null : d.path("profile").path("signature").asText(null))))
                        .vipType(p.path("vipType").asInt(account.path("account").path("vipType").asInt(0)))
                        .level(d == null ? null : d.path("level").asInt(0))
                        .listenSongs(d == null ? null : d.path("listenSongs").asInt(0))
                        .viaSiteAccount(id.viaSiteAccount())
                        .build();
                });
        });
    }

    public Mono<NcmMePlaylistList> playlists(MeIdentity identity) {
        return withUid(identity).flatMap(id -> call("user_playlist",
                Map.of("uid", id.uid(), "limit", "1000", "offset", "0"), id, "获取歌单")
            .map(body -> {
                List<NcmMePlaylist> items = MeMapper.playlists(body, id.uid());
                return NcmMePlaylistList.builder().userId(id.uid()).items(items).total(items.size()).build();
            }));
    }

    public Mono<Map<String, Object>> foliaAlbums(MeIdentity identity, int offset, int limit) {
        return call("album_sublist", Map.of("offset", String.valueOf(offset), "limit", String.valueOf(limit)),
            identity, "获取收藏专辑").map(body -> Map.of("albums", FoliaFeedPayload.collections(body.path("data")),
                "more", body.path("hasMore").asBoolean(false)));
    }

    public Mono<Map<String, Boolean>> albumSubscription(MeIdentity identity, String albumId) {
        return call("album_detail_dynamic", Map.of("id", albumId), identity, "查询专辑收藏状态").map(body -> {
            JsonNode subscribed = body.path("isSub");
            if (!subscribed.isBoolean()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未返回专辑收藏状态");
            }
            return Map.of("subscribed", subscribed.asBoolean());
        });
    }

    public Mono<Map<String, Boolean>> subscribeAlbum(MeIdentity identity, String albumId, boolean subscribed) {
        return call("album_sub", Map.of("id", albumId, "t", subscribed ? "1" : "0"), identity,
            subscribed ? "收藏专辑" : "取消收藏专辑").then(albumSubscription(identity, albumId));
    }

    public Mono<Map<String, Object>> foliaDailySongs(MeIdentity identity, boolean refresh) {
        return call("recommend_songs", Map.of("afresh", Boolean.toString(refresh)), identity, "获取每日推荐")
            .map(body -> Map.of("songs", FoliaFeedPayload.dailySongs(body)));
    }

    public Mono<Map<String, Object>> foliaRecommendedPlaylists(MeIdentity identity) {
        return call("recommend_resource", Map.of(), identity, "获取推荐歌单")
            .map(body -> Map.of("recommend", FoliaFeedPayload.collections(body.path("recommend"))));
    }

    public Mono<Map<String, Object>> foliaPersonalFm(MeIdentity identity) {
        return call("personal_fm", Map.of(), identity, "获取私人 FM")
            .map(body -> Map.of("songs", FoliaFeedPayload.songs(body, "data")));
    }

    public Mono<Map<String, Object>> foliaPersonalFm(MeIdentity identity, String mode, String submode) {
        if ("DEFAULT".equals(mode)) {
            return foliaPersonalFm(identity);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("mode", mode);
        if ("SCENE_RCMD".equals(mode) && submode != null) {
            query.put("submode", submode);
        }
        return call("personal_fm_mode", query, identity, "获取私人 FM")
            .map(body -> FoliaFeedPayload.songs(body, "data"))
            .filter(songs -> !songs.isEmpty())
            .map(songs -> Map.<String, Object>of("songs", songs))
            .onErrorResume(ResponseStatusException.class, error -> error.getStatusCode() == HttpStatus.BAD_REQUEST
                ? Mono.empty() : Mono.error(error))
            .switchIfEmpty(Mono.defer(() -> foliaPersonalFm(identity)));
    }

    public Mono<Map<String, List<String>>> foliaHistoryDates(MeIdentity identity) {
        return call("history_recommend_songs", Map.of(), identity, "获取历史日推").map(body -> {
            JsonNode dates = body.path("data").path("dates");
            if (!dates.isArray()) {
                dates = body.path("dates").isArray() ? body.path("dates") : body.path("data");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode row : dates) {
                String date = row.isTextual() ? row.asText() : row.path("date").asText();
                if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    result.add(date);
                }
            }
            return Map.of("dates", result);
        });
    }

    public Mono<Map<String, Object>> foliaHistorySongs(MeIdentity identity, String date) {
        return call("history_recommend_songs_detail", Map.of("date", date), identity, "获取当日日推")
            .map(body -> Map.of("songs", FoliaFeedPayload.dailySongs(body)));
    }

    public Mono<Map<String, Object>> foliaDailyStyleGroups(MeIdentity identity) {
        return call("recommend_category_configs", Map.of(), identity, "获取推荐风格").map(body -> {
            List<Map<String, Object>> groups = new ArrayList<>();
            for (JsonNode row : body.path("data").path("categorys")) {
                var group = dailyStyleGroup(row);
                if (!group.isEmpty()) {
                    groups.add(group);
                }
            }
            return Map.of("groups", groups);
        });
    }

    public Mono<Map<String, Object>> foliaDailyStyleSongs(MeIdentity identity) {
        return call("recommend_category_songs", Map.of(), identity, "获取风格推荐").map(body -> {
            JsonNode data = body.path("data");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("songs", FoliaFeedPayload.songs(data, "dailySongs"));
            JsonNode selected = data.path("tags");
            var tags = dailyStyleTags(selected);
            if (selected.hasNonNull("categoryId") && !tags.isEmpty()) {
                result.put("selection", Map.of("groupId", selected.path("categoryId").asText(),
                    "tagIds", tags.stream().map(tag -> tag.get("id")).toList()));
            }
            return result;
        });
    }

    private static Map<String, Object> dailyStyleGroup(JsonNode row) {
        var tags = dailyStyleTags(row);
        return row.hasNonNull("categoryId") && !tags.isEmpty()
            ? Map.of("id", row.path("categoryId").asText(), "name", row.path("categoryName").asText(), "tags", tags)
            : Map.of();
    }

    private static List<Map<String, String>> dailyStyleTags(JsonNode row) {
        List<Map<String, String>> tags = new ArrayList<>();
        for (JsonNode tag : row.path("tagVOList")) {
            if (tag.hasNonNull("tagId")) {
                String id = tag.path("tagId").asText();
                tags.add(Map.of("id", id, "name", tag.path("tagName").asText(id)));
            }
        }
        return tags;
    }

    public Mono<Void> saveDailyStyle(MeIdentity identity, String groupId, List<String> tagIds) {
        return call("recommend_category_set", Map.of("category", groupId, "tags", String.join(",", tagIds)),
            identity, "保存推荐风格").doOnNext(body -> {
                if (body.path("data").path("saveSuccess").isBoolean()
                    && !body.path("data").path("saveSuccess").asBoolean()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未确认推荐风格");
                }
            }).then();
    }

    public Mono<Map<String, Object>> dislikeRecommendation(MeIdentity identity, String songId) {
        return request("recommend_songs_dislike", Map.of("id", songId), identity, "更新推荐")
            .map(response -> {
                if (ncmCode(response) == 432) {
                    return Map.of("limitReached", true);
                }
                JsonNode replacement = ensureOk(response, "更新推荐").path("data");
                if (!replacement.hasNonNull("id")) {
                    return Map.of("limitReached", false);
                }
                return Map.of("limitReached", false, "replacement", FoliaFeedPayload.song(replacement));
            });
    }

    public Mono<Map<String, Object>> foliaHome(MeIdentity identity, String cursor) {
        return call("homepage_block_page", NcmFinderImpl.foliaHomeParams(cursor), identity, "获取探索推荐")
            .map(FoliaFeedPayload::home);
    }

    public Mono<Map<String, Object>> foliaCloud(MeIdentity identity, int offset, int limit) {
        return call("user_cloud", Map.of("offset", String.valueOf(offset), "limit", String.valueOf(limit)), identity,
            "获取音乐云盘").map(body -> {
                JsonNode rows = body.path("data");
                if (!rows.isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未返回云盘曲目列表");
                }
                List<Map<String, Object>> items = new ArrayList<>();
                for (JsonNode row : rows) {
                    String id = cloudId(row);
                    if (!MeParams.ID.matcher(id).matches()) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    item.put("name", row.path("songName").asText(row.path("fileName").asText("未知曲目")));
                    item.put("artist", row.path("artist").asText());
                    JsonNode song = row.path("simpleSong");
                    if (song.hasNonNull("id")) {
                        item.put("song", FoliaFeedPayload.song(song));
                    }
                    items.add(item);
                }
                int nextOffset = offset + rows.size();
                int count = body.path("count").asInt(nextOffset);
                return Map.of("items", items, "total", count, "nextOffset", nextOffset,
                    "hasMore", !rows.isEmpty() && body.path("hasMore").asBoolean(nextOffset < count));
            });
    }

    private static String cloudId(JsonNode row) {
        return row.path("songId").asText(row.path("simpleSong").path("id").asText());
    }

    private Mono<Void> requireCloudTrack(MeIdentity identity, String id) {
        return call("user_cloud_detail", Map.of("id", id), identity, "查找云盘曲目").flatMap(body -> {
            for (JsonNode item : body.path("data")) {
                if (id.equals(cloudId(item))) {
                    return Mono.empty();
                }
            }
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "云盘没有这首歌"));
        });
    }

    public Mono<Map<String, Object>> cloudPlay(MeIdentity identity, String id, String level) {
        return requireCloudTrack(identity, id)
            .then(Mono.defer(() -> plays.resolveForAccount(id, level, identity.cookie())))
            .map(result -> PlayerRouter.playBody(id, result, true));
    }

    public Mono<Map<String, String>> cloudLyric(MeIdentity identity, String id) {
        return requireCloudTrack(identity, id).then(Mono.defer(() -> withUid(identity)
            .flatMap(owner -> call("cloud_lyric_get", Map.of("uid", owner.uid(), "sid", id), owner, "获取云盘歌词"))))
            .map(body -> {
                JsonNode data = body.path("data");
                String lyric = body.path("lrc").asText(data.path("lrc").asText(
                    body.path("lyric").asText(data.path("lyric").asText(""))));
                return Map.of("lyric", lyric);
            });
    }

    public Mono<Void> reportPlayback(MeIdentity identity, String id, long playedSeconds, Long totalSeconds,
                                     String quality) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", id);
        params.put("time", String.valueOf(playedSeconds));
        if (totalSeconds != null) {
            params.put("total", String.valueOf(totalSeconds));
        }
        params.put("level", quality);
        params.put("bitrate", switch (quality) {
            case "standard" -> "128";
            case "lossless" -> "999";
            case "hires" -> "1999";
            default -> "320";
        });
        return call("scrobble_v1", params, identity, "同步听歌记录").then();
    }

    public Mono<NcmMePlaylistTracks> playlistTracks(MeIdentity identity, String playlistId, int offset, int limit) {
        return withUid(identity).flatMap(id -> playlistDetail(id, playlistId).flatMap(p -> {
            List<String> all = MeMapper.trackIds(p);
            NcmMePlaylist info = MeMapper.playlist(p, id.uid());
            return songs(id, slice(all, offset, limit)).map(items -> NcmMePlaylistTracks.builder()
                .playlist(info)
                .offset(offset)
                .limit(limit)
                .total(all.size())
                .items(items)
                .build());
        }));
    }

    public Mono<NcmMeSongPage> likes(MeIdentity identity, int offset, int limit) {
        return withUid(identity).flatMap(id -> likedIds(id).flatMap(all -> songs(id, slice(all, offset, limit))
            .map(items -> NcmMeSongPage.builder().offset(offset).limit(limit).total(all.size()).items(items).build())));
    }

    /** 批量查询是否已红心：请求里的每个 ID 都会出现在结果里。 */
    public Mono<NcmMeLikeCheck> checkLikes(MeIdentity identity, List<String> songIds) {
        return withUid(identity).flatMap(this::likedIds).map(all -> likeCheck(new HashSet<>(all), songIds));
    }

    public Mono<Map<String, Object>> foliaLikedSongs(MeIdentity identity) {
        return withUid(identity).flatMap(this::likedIds).map(ids -> Map.of("ids", ids, "whole", true));
    }

    static NcmMeLikeCheck likeCheck(Set<String> liked, List<String> songIds) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String s : songIds) {
            map.put(s, liked.contains(s));
        }
        return NcmMeLikeCheck.builder().liked(map).build();
    }

    /** 喜欢的歌曲 ID（最近红心的在前），按用户缓存 60 秒。 */
    Mono<List<String>> likedIds(MeIdentity id) {
        return likeCache.get(id.username(), id.uid(),
            () -> call("likelist", Map.of("uid", id.uid()), id, "获取喜欢的歌").map(body -> {
                if (!body.path("ids").isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未返回喜欢的歌曲列表");
                }
                return MeMapper.likedIds(body);
            }));
    }

    public Mono<NcmMeRecentRecords> recent(MeIdentity id) {
        String limit = String.valueOf(RECENT_LIMIT);
        Mono<Optional<List<NcmMePlaylistRecord>>> playlists = withUid(id)
            .flatMap(x -> call("record_recent_playlist", Map.of("limit", limit), x, "获取最近播放的歌单")
                .map(body -> Optional.of(slice(MeMapper.recentPlaylists(body, x.uid()), 0, RECENT_LIMIT))))
            .onErrorReturn(Optional.empty());
        return call("record_recent_song", Map.of("limit", limit), id, "获取最近播放")
            .map(body -> slice(MeMapper.recentSongs(body), 0, RECENT_LIMIT))
            .zipWith(playlists, (songs, lists) -> NcmMeRecentRecords.builder().songs(songs)
                .playlists(lists.orElseGet(List::of)).playlistsUnavailable(lists.isEmpty()).build());
    }

    public Mono<NcmMeRankRecords> rank(MeIdentity identity, String type) {
        boolean week = !"all".equals(type);
        return withUid(identity).flatMap(id -> call("user_record",
                Map.of("uid", id.uid(), "type", week ? "1" : "0"), id, "获取听歌排行")
            .map(body -> NcmMeRankRecords.builder().type(week ? "week" : "all").items(MeMapper.rank(body, week))
                .build()));
    }

    // ---------- 写操作 ----------

    public Mono<NcmMeLikeResult> like(MeIdentity id, String songId, boolean like) {
        return call("like", Map.of("id", songId, "like", like ? "true" : "false"), id, like ? "红心" : "取消红心")
            // 无论成败都清掉缓存，下次查询以网易云为准
            .doOnNext(x -> likeCache.invalidate(id.username()))
            .doOnError(e -> likeCache.invalidate(id.username()))
            .doOnCancel(() -> likeCache.invalidate(id.username()))
            .map(x -> NcmMeLikeResult.builder().songId(songId).liked(like).build());
    }

    public Mono<NcmMePlaylist> createPlaylist(MeIdentity id, String name, int privacy) {
        return call("playlist_create", Map.of("name", name, "privacy", String.valueOf(privacy), "type", "NORMAL"),
            id, "新建歌单").map(body -> {
                JsonNode p = body.path("playlist");
                NcmMePlaylist out;
                if (p.hasNonNull("id")) {
                    out = MeMapper.playlist(p, id.uid());
                } else {
                    String newId = body.path("id").asText("");
                    if (!MeParams.ID.matcher(newId).matches() || "0".equals(newId)) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未返回新歌单 ID，请刷新歌单列表");
                    }
                    out = NcmMePlaylist.builder().id(newId).name(name).trackCount(0).playCount(0L)
                        .likedSongs(false).privacy(privacy == MeParams.PRIVACY_PRIVATE)
                        .sitePath(MeMapper.PLAYLIST_PATH + newId).build();
                }
                out.setOwn(true);
                return out;
            });
    }

    public Mono<Void> deletePlaylist(MeIdentity identity, String playlistId) {
        return withUid(identity).flatMap(id -> requireOwn(id, playlistId, true)
            .then(call("playlist_delete", Map.of("id", playlistId), id, "删除歌单"))).then();
    }

    public Mono<NcmMeTracksResult> tracks(MeIdentity identity, String playlistId, List<String> songIds, boolean add) {
        String op = add ? "add" : "del";
        return withUid(identity).flatMap(id -> requireOwn(id, playlistId, false)
            .then(call("playlist_tracks", Map.of("op", op, "pid", playlistId, "tracks", String.join(",", songIds)),
                id, add ? "加入歌单" : "从歌单移除")))
            .map(x -> NcmMeTracksResult.builder().playlistId(playlistId).op(op).songIds(songIds).build());
    }

    public Mono<NcmMeSubscriptionResult> subscribe(MeIdentity id, String playlistId, boolean subscribe) {
        return call("playlist_subscribe", Map.of("id", playlistId, "t", subscribe ? "1" : "0"), id,
            subscribe ? "收藏歌单" : "取消收藏歌单")
            .map(x -> NcmMeSubscriptionResult.builder().playlistId(playlistId).subscribed(subscribe).build());
    }

    public Mono<NcmMeSubscriptionResult> subscription(MeIdentity identity, String playlistId) {
        return withUid(identity).flatMap(id -> playlistDetail(id, playlistId).flatMap(detail -> {
            boolean own = id.uid().equals(MeMapper.creatorId(detail));
            JsonNode subscribed = detail.path("subscribed");
            Mono<Boolean> state = own || subscribed.isBoolean() ? Mono.just(!own && subscribed.asBoolean())
                : playlists(id).map(list -> list.getItems().stream()
                    .anyMatch(p -> playlistId.equals(p.getId()) && !Boolean.TRUE.equals(p.getOwn())));
            return state.map(value -> NcmMeSubscriptionResult.builder().playlistId(playlistId)
                .subscribed(value).own(own).build());
        }));
    }

    /** 先查歌单详情，确认是本人创建的。 */
    Mono<JsonNode> requireOwn(MeIdentity id, String playlistId, boolean forDelete) {
        return playlistDetail(id, playlistId).map(p -> checkOwn(p, playlistId, id.uid(), forDelete));
    }

    /**
     * “只能改自己创建的歌单”的判断：歌单的 creator.userId（没有时用 userId）必须等于本人 UID。
     * 删除时额外禁止删除“我喜欢的音乐”。不满足时抛 403。
     *
     * @param playlist playlist_detail 返回里的 playlist 节点
     */
    static JsonNode checkOwn(JsonNode playlist, String playlistId, String myUid, boolean forDelete) {
        String creator = MeMapper.creatorId(playlist);
        if (myUid == null || creator == null || !myUid.equals(creator)) {
            throw forbidden(NOT_OWN);
        }
        if (forDelete && playlist.path("specialType").asInt(0) == MeMapper.LIKED_SPECIAL_TYPE) {
            throw forbidden(LIKED_PLAYLIST);
        }
        return playlist;
    }

    /** playlist_detail 的 playlist 节点；歌单不存在、或返回的 ID 对不上时 404。 */
    Mono<JsonNode> playlistDetail(MeIdentity id, String playlistId) {
        return call("playlist_detail", Map.of("id", playlistId, "s", "0"), id, "获取歌单").map(body -> {
            JsonNode p = body.path("playlist");
            if (!p.hasNonNull("id") || !playlistId.equals(p.path("id").asText())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, PLAYLIST_NOT_FOUND);
            }
            return p;
        });
    }

    /** 用 song_detail 补全歌曲信息，按 ids 的顺序返回。 */
    Mono<List<NcmMeSong>> songs(MeIdentity id, List<String> ids) {
        if (ids.isEmpty()) {
            return Mono.just(List.of());
        }
        return call("song_detail", Map.of("ids", String.join(",", ids)), id, "获取歌曲信息")
            .map(body -> MeMapper.songsInOrder(body, ids));
    }

    static <T> List<T> slice(List<T> all, int offset, int limit) {
        int from = Math.min(Math.max(0, offset), all.size());
        int to = Math.min(all.size(), from + Math.max(0, limit));
        return all.subList(from, to);
    }

    // ---------- 调用网易云 ----------

    /** 用本人的 Cookie 执行一个接口；返回成功时的 body，失败时抛 ResponseStatusException。 */
    Mono<JsonNode> call(String api, Map<String, String> query, MeIdentity id, String action) {
        return request(api, query, id, action).map(r -> ensureOk(r, action));
    }

    private Mono<NcmResponse> request(String api, Map<String, String> query, MeIdentity id, String action) {
        ApiDef def = registry.find(api).orElse(null);
        if (def == null || !def.executable()) {
            return Mono.error(new IllegalStateException("接口 " + api + " 不可用"));
        }
        return settings.basic().flatMap(s -> executor.execute(def, new LinkedHashMap<>(query),
                new RequestOptions(def.crypto(), id.cookie(), null, s.realIp(), s.useRandomCnIp())))
            .timeout(REQUEST_TIMEOUT)
            .onErrorMap(TimeoutException.class, e -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                action + "超时，请稍后重试"));
    }

    static JsonNode ensureOk(NcmResponse r, String action) {
        int code = ncmCode(r);
        if (code == 200) {
            if (r.body() == null || !r.body().isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, action + "失败，网易云返回的数据无效");
            }
            return r.body();
        }
        if (code == 301) {
            throw forbidden(EXPIRED);
        }
        String msg = message(r.body());
        // 网易云常用 502 表示业务错误（例如歌曲已在歌单里），按 400 处理；其他 5xx 视为网易云或网络故障
        HttpStatus status = code == 429 ? HttpStatus.TOO_MANY_REQUESTS
            : r.status() >= 500 || (code >= 500 && code != 502) ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
        log.debug("[ncm] 个人音乐空间：{}失败，网易云返回 {} {}", action, code, msg);
        throw new ResponseStatusException(status,
            action + "失败" + (msg == null ? "" : "：" + msg) + "（网易云返回 " + code + "）");
    }

    /**
     * 网易云的业务码：优先 body.code；playlist_tracks 成功时返回 { status, body: { code } }，取里面的 code；
     * 都没有时用 HTTP 状态。
     */
    static int ncmCode(NcmResponse r) {
        if (r.status() != 200) {
            return r.status();
        }
        JsonNode b = r.body();
        if (b != null && b.has("code")) {
            return b.path("code").asInt(200);
        }
        if (b != null && b.path("body").has("code")) {
            return b.path("body").path("code").asInt(200);
        }
        return 200;
    }

    static String message(JsonNode body) {
        if (body == null) {
            return null;
        }
        for (JsonNode n : List.of(body, body.path("body"))) {
            for (String k : List.of("message", "msg")) {
                String v = n.path(k).asText("");
                if (!v.isBlank()) {
                    return v.length() > 200 ? v.substring(0, 200) : v;
                }
            }
        }
        return null;
    }

    static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

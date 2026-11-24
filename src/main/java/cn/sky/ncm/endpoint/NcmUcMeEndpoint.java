package cn.sky.ncm.endpoint;

import static cn.sky.ncm.endpoint.EndpointSupport.jsonBody;
import static cn.sky.ncm.endpoint.EndpointSupport.op;
import static cn.sky.ncm.endpoint.EndpointSupport.returns;
import static cn.sky.ncm.endpoint.EndpointSupport.returnsNothing;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.endpoint.dto.NcmMeLikeCheck;
import cn.sky.ncm.endpoint.dto.NcmMeLikeResult;
import cn.sky.ncm.endpoint.dto.NcmMePlaylist;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistCreateRequest;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistList;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistTracks;
import cn.sky.ncm.endpoint.dto.NcmMeProfile;
import cn.sky.ncm.endpoint.dto.NcmMeRankRecords;
import cn.sky.ncm.endpoint.dto.NcmMeRecentRecords;
import cn.sky.ncm.endpoint.dto.NcmMeSongPage;
import cn.sky.ncm.endpoint.dto.NcmMeSubscriptionResult;
import cn.sky.ncm.endpoint.dto.NcmMeTracksRequest;
import cn.sky.ncm.endpoint.dto.NcmMeTracksResult;
import cn.sky.ncm.me.MeIdentity;
import cn.sky.ncm.me.MeParams;
import cn.sky.ncm.me.MeService;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 个人中心的歌单、喜欢和记录接口。me/- 表示当前登录用户，后续路径是 Halo 的固定子资源。
 * <pre>
 * GET    me/-/profile                           绑定的网易云账号概况
 * GET    me/-/playlists                         创建 + 收藏的歌单（“我喜欢的音乐”置顶）
 * POST   me/-/playlists                         新建歌单 { name, privacy }
 * DELETE me/-/playlists/{id}                    删除歌单（只能删自己创建的）
 * GET    playlists/{id}/tracks?offset=&limit=   歌单里的歌（分页）
 * POST   playlists/{id}/tracks                 加入歌曲 { songIds }（只能改自己创建的）
 * DELETE playlists/{id}/tracks?songIds=         移出歌曲（只能改自己创建的）
 * GET    me/-/likes?offset=&limit=              喜欢的歌（分页）
 * GET    me/-/likes/check?ids=1,2,3             是否已红心
 * PUT    me/-/likes/{songId}                    红心
 * DELETE me/-/likes/{songId}                    取消红心
 * GET    me/-/records/recent                    最近播放
 * GET    me/-/records/rank?type=week|all        听歌排行
 * PUT    me/-/subscriptions/{playlistId}        收藏歌单
 * DELETE me/-/subscriptions/{playlistId}        取消收藏歌单
 * </pre>
 * 未登录 401；没有绑定网易云账号 403（带引导文案）；写操作要带请求头 X-NCM-Request: 1；
 * 按用户名限流（设置「网络与限流 → 个人请求」）。
 */
@Slf4j
@Component
public class NcmUcMeEndpoint implements CustomEndpoint {

    static final String TAG = "uc.api.ncm.halo.run/v1alpha1/Me";
    static final String MINE = "me/-/";
    static final String REQUEST_HEADER = "X-NCM-Request";
    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final MeService me;
    private final RateLimiter limiter;
    private final SettingService settings;
    private final NcmBootstrap bootstrap;

    public NcmUcMeEndpoint(MeService me, RateLimiter limiter, SettingService settings, NcmBootstrap bootstrap) {
        this.me = me;
        this.limiter = limiter;
        this.settings = settings;
        this.bootstrap = bootstrap;
    }

    /** 校验完参数后要执行的操作。 */
    private interface Action extends Function<MeIdentity, Mono<?>> {
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET(MINE + "profile", r -> handle(r, false, Mono.just(me::profile)),
                b -> returns(op(b, "GetMyNcmProfile", TAG, "绑定的网易云账号概况"), NcmMeProfile.class))
            .GET(MINE + "playlists", r -> handle(r, false, Mono.just(me::playlists)),
                b -> returns(op(b, "ListMyNcmPlaylists", TAG,
                    "本人创建和收藏的歌单：“我喜欢的音乐”在最前，然后是自己创建的，最后是收藏的"), NcmMePlaylistList.class))
            .GET(MINE + "albums", this::albums, b -> returns(pageParams(
                op(b, "ListMyNcmAlbums", TAG, "本人收藏的专辑")), java.util.Map.class))
            .GET(MINE + "cloud", this::cloud, b -> returns(pageParams(
                op(b, "ListMyNcmCloudTracks", TAG, "音乐云盘曲目")), java.util.Map.class))
            .GET(MINE + "cloud/{id}/play", this::cloudPlay, b -> returns(queryParam(pathParam(
                op(b, "ResolveMyNcmCloudTrack", TAG, "本人云盘曲目的播放地址"), "id", "云盘曲目 ID"),
                "level", "期望音质", false), java.util.Map.class))
            .GET(MINE + "cloud/{id}/lyric", this::cloudLyric, b -> returns(pathParam(
                op(b, "GetMyNcmCloudLyrics", TAG, "本人云盘曲目的歌词"), "id", "云盘曲目 ID"), java.util.Map.class))
            .GET(MINE + "albums/{id}/subscription", r -> albumSubscription(r, null), b -> returns(pathParam(
                op(b, "GetMyNcmAlbumSubscription", TAG, "查询专辑收藏状态"), "id", "专辑 ID"), java.util.Map.class))
            .PUT(MINE + "albums/{id}/subscription", r -> albumSubscription(r, true), b -> returns(writeHeader(pathParam(
                op(b, "SubscribeMyNcmAlbum", TAG, "收藏专辑"), "id", "专辑 ID")), java.util.Map.class))
            .DELETE(MINE + "albums/{id}/subscription", r -> albumSubscription(r, false), b -> returns(writeHeader(pathParam(
                op(b, "UnsubscribeMyNcmAlbum", TAG, "取消收藏专辑"), "id", "专辑 ID")), java.util.Map.class))
            .GET(MINE + "recommendations/daily", r -> handle(r, false, Mono.just(
                id -> me.foliaDailySongs(id, "true".equals(r.queryParam("refresh").orElse(""))))),
                b -> returns(queryParam(op(b, "ListMyNcmDailySongs", TAG, "每日推荐歌曲"),
                    "refresh", "请求更新每日推荐", false), java.util.Map.class))
            .GET(MINE + "recommendations/playlists", r -> handle(r, false, Mono.just(me::foliaRecommendedPlaylists)),
                b -> returns(op(b, "ListMyNcmRecommendedPlaylists", TAG, "本人推荐歌单"), java.util.Map.class))
            .GET(MINE + "recommendations/fm", this::personalFm, b -> returns(queryParam(queryParam(
                op(b, "ListMyNcmPersonalFm", TAG, "私人 FM 歌曲"), "mode", "私人 FM 模式", false),
                "submode", "场景子模式", false), java.util.Map.class))
            .GET(MINE + "recommendations/daily/history", r -> handle(r, false, Mono.just(me::foliaHistoryDates)),
                b -> returns(op(b, "ListMyNcmRecommendationDates", TAG, "历史日推日期"), java.util.Map.class))
            .GET(MINE + "recommendations/daily/history/{date}", this::historySongs,
                b -> returns(pathParam(op(b, "ListMyNcmHistoricRecommendations", TAG, "历史日推曲目"),
                    "date", "推荐日期 (YYYY-MM-DD)"), java.util.Map.class))
            .GET(MINE + "recommendations/style/groups", r -> handle(r, false, Mono.just(me::foliaDailyStyleGroups)),
                b -> returns(op(b, "ListMyNcmRecommendationStyles", TAG, "推荐风格选项"), java.util.Map.class))
            .GET(MINE + "recommendations/style", r -> handle(r, false, Mono.just(me::foliaDailyStyleSongs)),
                b -> returns(op(b, "GetMyNcmStyleRecommendations", TAG, "风格推荐与当前选择"), java.util.Map.class))
            .PUT(MINE + "recommendations/style", this::saveDailyStyle, b -> returnsNothing(writeHeader(jsonBody(
                op(b, "SaveMyNcmRecommendationStyle", TAG, "保存推荐风格"), DailyStyleRequest.class))))
            .POST(MINE + "recommendations/dislike/{id}", this::dislikeRecommendation, b -> returns(writeHeader(
                pathParam(op(b, "DislikeMyNcmRecommendation", TAG, "不喜欢这首推荐歌曲"), "id", "歌曲 ID")),
                java.util.Map.class))
            .GET(MINE + "recommendations/explore", this::explore, b -> returns(queryParam(
                op(b, "ReadMyNcmExplore", TAG, "本人探索推荐"), "cursor", "下一页标记", false), java.util.Map.class))
            .POST(MINE + "playlists", this::createPlaylist, b -> returns(writeHeader(jsonBody(
                op(b, "CreateMyNcmPlaylist", TAG, "新建歌单"), NcmMePlaylistCreateRequest.class)), NcmMePlaylist.class))
            .DELETE(MINE + "playlists/{id}", this::deletePlaylist, b -> returnsNothing(writeHeader(pathParam(
                op(b, "DeleteMyNcmPlaylist", TAG, "删除歌单；只能删除自己创建的，“我喜欢的音乐”不能删除"), "id", "歌单 ID"))))
            .GET("playlists/{id}/tracks", this::playlistTracks, b -> returns(pageParams(pathParam(
                op(b, "ListMyNcmPlaylistTracks", TAG, "歌单信息和歌曲（分页，每页最多 100 首）"), "id", "歌单 ID")),
                NcmMePlaylistTracks.class))
            .POST("playlists/{id}/tracks", this::addTracks, b -> returns(writeHeader(jsonBody(pathParam(
                op(b, "AddMyNcmPlaylistTracks", TAG, "把歌曲加入歌单；只能改自己创建的歌单"), "id", "歌单 ID"),
                NcmMeTracksRequest.class)), NcmMeTracksResult.class))
            .DELETE("playlists/{id}/tracks", this::removeTracks, b -> returns(writeHeader(queryParam(pathParam(
                op(b, "RemoveMyNcmPlaylistTracks", TAG, "把歌曲移出歌单；只能改自己创建的歌单"), "id", "歌单 ID"),
                "songIds", "歌曲 ID，英文逗号分隔，最多 100 个", true)), NcmMeTracksResult.class))
            .GET(MINE + "likes/check", this::checkLikes, b -> returns(queryParam(
                op(b, "CheckMyNcmLikes", TAG, "批量查询歌曲是否已红心；喜欢列表按用户缓存约 60 秒"),
                "ids", "歌曲 ID，英文逗号分隔，最多 200 个", true), NcmMeLikeCheck.class))
            .GET(MINE + "likes/ids", r -> handle(r, false, Mono.just(me::foliaLikedSongs)),
                b -> returns(op(b, "ListMyNcmLikedSongIds", TAG, "喜欢的歌曲 ID"), java.util.Map.class))
            .GET(MINE + "likes", this::likes, b -> returns(pageParams(
                op(b, "ListMyNcmLikes", TAG, "喜欢的歌（分页，每页最多 100 首）")), NcmMeSongPage.class))
            .PUT(MINE + "likes/{songId}", r -> like(r, true), b -> returns(writeHeader(pathParam(
                op(b, "LikeMyNcmSong", TAG, "红心一首歌"), "songId", "歌曲 ID")), NcmMeLikeResult.class))
            .DELETE(MINE + "likes/{songId}", r -> like(r, false), b -> returns(writeHeader(pathParam(
                op(b, "UnlikeMyNcmSong", TAG, "取消红心"), "songId", "歌曲 ID")), NcmMeLikeResult.class))
            .GET(MINE + "records/recent", r -> handle(r, false, Mono.just(me::recent)),
                b -> returns(op(b, "ListMyNcmRecentRecords", TAG, "最近播放的歌曲和歌单"), NcmMeRecentRecords.class))
            .GET(MINE + "records/rank", this::rank, b -> returns(queryParam(
                op(b, "ListMyNcmRankRecords", TAG, "听歌排行"), "type", "week（最近一周，默认）或 all（所有时间）", false),
                NcmMeRankRecords.class))
            .POST(MINE + "records/playback", this::reportPlayback, b -> returnsNothing(writeHeader(jsonBody(
                op(b, "ReportMyNcmPlayback", TAG, "同步本人实际听歌记录"), PlaybackReportRequest.class))))
            .GET(MINE + "subscriptions/{playlistId}", this::subscription, b -> returns(pathParam(
                op(b, "GetMyNcmSubscription", TAG, "查询歌单归属与当前账号收藏状态"),
                "playlistId", "歌单 ID"), NcmMeSubscriptionResult.class))
            .PUT(MINE + "subscriptions/{playlistId}", r -> subscribe(r, true), b -> returns(writeHeader(pathParam(
                op(b, "SubscribeMyNcmPlaylist", TAG, "收藏歌单"), "playlistId", "歌单 ID")), NcmMeSubscriptionResult.class))
            .DELETE(MINE + "subscriptions/{playlistId}", r -> subscribe(r, false), b -> returns(writeHeader(pathParam(
                op(b, "UnsubscribeMyNcmPlaylist", TAG, "取消收藏歌单"), "playlistId", "歌单 ID")),
                NcmMeSubscriptionResult.class))
            .build();
    }

    // ---------- 各接口 ----------

    Mono<ServerResponse> albums(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            int offset = MeParams.offset(r.queryParam("offset"));
            int limit = MeParams.limit(r.queryParam("limit"));
            return (Action) id -> me.foliaAlbums(id, offset, limit);
        }));
    }

    Mono<ServerResponse> cloud(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            int offset = MeParams.offset(r.queryParam("offset"));
            int limit = MeParams.limit(r.queryParam("limit"));
            return (Action) identity -> me.foliaCloud(identity, offset, limit);
        }));
    }

    Mono<ServerResponse> cloudPlay(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "云盘曲目 ID");
            String level = r.queryParam("level").orElse("standard");
            return (Action) identity -> me.cloudPlay(identity, id, level);
        }));
    }

    Mono<ServerResponse> cloudLyric(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "云盘曲目 ID");
            return (Action) identity -> me.cloudLyric(identity, id);
        }));
    }

    public record PlaybackReportRequest(String songId, long playedSeconds, Long totalSeconds, String quality) {
    }

    Mono<ServerResponse> reportPlayback(ServerRequest r) {
        return handle(r, true, readJson(r, PlaybackReportRequest.class).map(body -> {
            String id = MeParams.id(body.songId(), "歌曲 ID");
            if (body.playedSeconds() < 30 || (body.totalSeconds() != null && body.totalSeconds() < body.playedSeconds())) {
                throw new ServerWebInputException("播放时长无效");
            }
            String quality = switch (body.quality() == null ? "" : body.quality()) {
                case "standard", "lossless", "hires" -> body.quality();
                default -> "exhigh";
            };
            return (Action) identity -> me.reportPlayback(identity, id, body.playedSeconds(), body.totalSeconds(), quality);
        }));
    }

    Mono<ServerResponse> albumSubscription(ServerRequest r, Boolean subscribed) {
        return handle(r, subscribed != null, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "专辑 ID");
            return (Action) identity -> subscribed == null ? me.albumSubscription(identity, id)
                : me.subscribeAlbum(identity, id, subscribed);
        }));
    }

    Mono<ServerResponse> personalFm(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String mode = r.queryParam("mode").filter(value -> !value.isEmpty()).orElse("DEFAULT");
            String submode = r.queryParam("submode").filter(value -> !value.isEmpty()).orElse(null);
            if (!mode.matches("[A-Za-z0-9_-]{1,40}")
                || (submode != null && !submode.matches("[A-Za-z0-9_-]{1,40}"))) {
                throw new ServerWebInputException("私人 FM 模式无效");
            }
            return (Action) identity -> me.foliaPersonalFm(identity, mode, submode);
        }));
    }

    Mono<ServerResponse> historySongs(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String date = r.pathVariable("date");
            try {
                if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new ServerWebInputException("推荐日期应为 YYYY-MM-DD");
                }
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw new ServerWebInputException("推荐日期无效");
            }
            return (Action) identity -> me.foliaHistorySongs(identity, date);
        }));
    }

    public record DailyStyleRequest(String groupId, List<String> tagIds) {
    }

    Mono<ServerResponse> saveDailyStyle(ServerRequest r) {
        return handle(r, true, readJson(r, DailyStyleRequest.class).map(body -> {
            String group = MeParams.id(body.groupId(), "风格分组");
            List<String> tags = MeParams.idList(body.tagIds(), "风格标签", MeParams.MAX_TRACK_IDS);
            return (Action) identity -> me.saveDailyStyle(identity, group, tags);
        }));
    }

    Mono<ServerResponse> dislikeRecommendation(ServerRequest r) {
        return handle(r, true, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "歌曲 ID");
            return (Action) identity -> me.dislikeRecommendation(identity, id);
        }));
    }

    Mono<ServerResponse> explore(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String cursor = r.queryParam("cursor").orElse("");
            if (cursor.length() > 4096) {
                throw new ServerWebInputException("探索分页标记过长");
            }
            return (Action) id -> me.foliaHome(id, cursor);
        }));
    }

    Mono<ServerResponse> playlistTracks(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "id");
            int offset = MeParams.offset(r.queryParam("offset"));
            int limit = MeParams.limit(r.queryParam("limit"));
            return (Action) x -> me.playlistTracks(x, id, offset, limit);
        }));
    }

    Mono<ServerResponse> likes(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            int offset = MeParams.offset(r.queryParam("offset"));
            int limit = MeParams.limit(r.queryParam("limit"));
            return (Action) x -> me.likes(x, offset, limit);
        }));
    }

    Mono<ServerResponse> checkLikes(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            List<String> ids = MeParams.idList(r.queryParam("ids").orElse(null), "ids", MeParams.MAX_CHECK_IDS);
            return (Action) x -> me.checkLikes(x, ids);
        }));
    }

    Mono<ServerResponse> rank(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String type = MeParams.rankType(r.queryParam("type"));
            return (Action) x -> me.rank(x, type);
        }));
    }

    Mono<ServerResponse> like(ServerRequest r, boolean like) {
        return handle(r, true, Mono.fromCallable(() -> {
            String songId = MeParams.id(r.pathVariable("songId"), "songId");
            return (Action) x -> me.like(x, songId, like);
        }));
    }

    Mono<ServerResponse> subscription(ServerRequest r) {
        return handle(r, false, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("playlistId"), "playlistId");
            return (Action) who -> me.subscription(who, id);
        }));
    }

    Mono<ServerResponse> subscribe(ServerRequest r, boolean subscribe) {
        return handle(r, true, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("playlistId"), "playlistId");
            return (Action) x -> me.subscribe(x, id, subscribe);
        }));
    }

    Mono<ServerResponse> createPlaylist(ServerRequest r) {
        return handle(r, true, readJson(r, NcmMePlaylistCreateRequest.class).map(b -> {
            String name = MeParams.playlistName(b.getName());
            int privacy = MeParams.privacy(b.getPrivacy());
            return (Action) x -> me.createPlaylist(x, name, privacy);
        }));
    }

    Mono<ServerResponse> deletePlaylist(ServerRequest r) {
        return handle(r, true, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "id");
            return (Action) x -> me.deletePlaylist(x, id);
        }));
    }

    Mono<ServerResponse> addTracks(ServerRequest r) {
        return handle(r, true, Mono.fromCallable(() -> MeParams.id(r.pathVariable("id"), "id"))
            .flatMap(id -> readJson(r, NcmMeTracksRequest.class).map(b -> {
                List<String> songIds = MeParams.idList(b.getSongIds(), "songIds", MeParams.MAX_TRACK_IDS);
                return (Action) x -> me.tracks(x, id, songIds, true);
            })));
    }

    Mono<ServerResponse> removeTracks(ServerRequest r) {
        return handle(r, true, Mono.fromCallable(() -> {
            String id = MeParams.id(r.pathVariable("id"), "id");
            List<String> songIds = MeParams.idList(r.queryParam("songIds").orElse(null), "songIds",
                MeParams.MAX_TRACK_IDS);
            return (Action) x -> me.tracks(x, id, songIds, false);
        }));
    }

    // ---------- 公共流程 ----------

    /**
     * 登录检查 → 写操作的防跨站检查 → 参数校验 → 限流 → 解析本人的网易云身份 → 初始化设备 → 执行。
     * 操作返回空（例如删除歌单）时响应 204。
     */
    Mono<ServerResponse> handle(ServerRequest r, boolean write, Mono<Action> prepare) {
        return EndpointSupport.username()
            .flatMap(username -> {
                if (write) {
                    guard(r);
                }
                return prepare.flatMap(action -> settings.advanced()
                    .flatMap(adv -> limiter.tryAcquire("me:" + username, adv.userLimit())
                        ? me.identity(username)
                        : Mono.<MeIdentity>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求太频繁，请稍后再试")))
                    .flatMap(id -> bootstrap.ready().then(Mono.<Object>defer(() -> action.apply(id)))));
            })
            .flatMap(body -> ServerResponse.ok().cacheControl(PRIVATE)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(json(body)))
            .switchIfEmpty(Mono.defer(() -> ServerResponse.noContent().cacheControl(PRIVATE).build()))
            .onErrorMap(e -> !(e instanceof ResponseStatusException), e -> {
                log.error("[ncm] 个人音乐空间接口 {} {} 出错", r.method(), r.uri().getPath(), e);
                return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "插件内部错误（" + e.getClass().getSimpleName() + "），详情见 Halo 日志");
            });
    }

    static void guard(ServerRequest r) {
        if (!"1".equals(r.headers().firstHeader(REQUEST_HEADER))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少请求头 X-NCM-Request: 1");
        }
    }

    /** 用插件自己的 Jackson 2 解析请求体（Halo 2.23+ 默认的编解码器是 Jackson 3）。 */
    static <T> Mono<T> readJson(ServerRequest r, Class<T> type) {
        return r.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(text -> {
                if (text.isBlank()) {
                    throw new ServerWebInputException("缺少请求体");
                }
                try {
                    T v = MAPPER.readValue(text, type);
                    if (v == null) {
                        throw new ServerWebInputException("缺少请求体");
                    }
                    return v;
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new ServerWebInputException("请求体不是有效的 JSON");
                }
            });
    }

    /** DTO → JSON 字符串，值为 null 的字段不输出。 */
    static String json(Object dto) {
        return Js.json(MAPPER.convertValue(dto, Object.class));
    }

    // ---------- OpenAPI 描述 ----------

    static Builder pathParam(Builder b, String name, String description) {
        return b.parameter(parameterBuilder().name(name).in(ParameterIn.PATH).required(true)
            .description(description).implementation(String.class));
    }

    static Builder queryParam(Builder b, String name, String description, boolean required) {
        return b.parameter(parameterBuilder().name(name).in(ParameterIn.QUERY).required(required)
            .description(description).implementation(String.class));
    }

    static Builder pageParams(Builder b) {
        return b.parameter(parameterBuilder().name("offset").in(ParameterIn.QUERY).required(false)
                .description("从第几条开始，默认 0").implementation(Integer.class))
            .parameter(parameterBuilder().name("limit").in(ParameterIn.QUERY).required(false)
                .description("每页条数，1~100，默认 30").implementation(Integer.class));
    }

    static Builder writeHeader(Builder b) {
        return b.parameter(parameterBuilder().name(REQUEST_HEADER).in(ParameterIn.HEADER).required(true)
            .description("固定为 1，防止跨站请求伪造").implementation(String.class));
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("uc.api.ncm.halo.run/v1alpha1");
    }
}

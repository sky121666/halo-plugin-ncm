package cn.sky.ncm.api.more;

import static cn.sky.ncm.api.more.Support.blank;
import static cn.sky.ncm.api.more.Support.cat;
import static cn.sky.ncm.api.more.Support.def;
import static cn.sky.ncm.api.more.Support.map;
import static cn.sky.ncm.api.more.Support.or;

import cn.sky.ncm.api.ApiGroup;
import cn.sky.ncm.api.UploadFile;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 参数处理稍复杂、但只发一次请求的接口。 */
@Component
public class SimpleApis implements ApiGroup {

    static final Map<String, String> RESOURCE_TYPE_ID = Map.of(
        "0", "0", "1", "5", "2", "0", "3", "3", "4", "1", "5", "62", "6", "2", "7", "14");
    static final Set<Integer> PRIVACY_VALUES = Set.of(0, 1, 2, 6);
    static final DateTimeFormatter LOCALE_STRING =
        DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.US);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NcmClient client;

    public SimpleApis(NcmClient client) {
        this.client = client;
    }

    @Override
    public Set<String> names() {
        return Set.of("aidj_content_rcmd", "album_songsaleboard", "artist_list", "comment_hug_list",
            "comment_info_list", "deviceinfo_center_upload", "dj_program", "dj_toplist", "event_privacy",
            "hug_comment", "listentogether_sync_list_command", "playlist_import_name_task_create",
            "playlist_subscribe", "recommend_category_set", "register_cellphone", "related_allvideo",
            "rep_ugc_exam_info_get", "rep_ugc_exam_question_single_get", "rep_ugc_exam_result_get",
            "rep_ugc_exam_start", "rep_ugc_exam_submit", "search_match", "song_wiki_info", "summary_annual",
            "thinktank_audit_resource_update", "user_bindingcellphone", "user_cloud_detail", "user_follow_mixed",
            "vip_timemachine", "relay_play_state_submit", "song_download_url_v1", "song_url_ncmget",
            "login_refresh");
    }

    @Override
    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        try {
            return switch (name) {
                case "aidj_content_rcmd" -> aidj(q, opt);
                case "album_songsaleboard" -> {
                    Map<String, Object> data = map("albumType", or(q.get("albumType"), (Object) 0L));
                    String type = or(q.get("type"), "daily");
                    if ("year".equals(type)) {
                        data.put("year", q.get("year"));
                        data.values().removeIf(v -> v == null);
                    }
                    yield client.request("/api/feealbum/songsaleboard/" + type + "/type", data, opt.withCrypto("weapi"));
                }
                case "artist_list" -> client.request("/api/v1/artist/list", map(
                    "initial", artistInitial(q.get("initial")),
                    "offset", or(q.get("offset"), (Object) 0L),
                    "limit", or(q.get("limit"), (Object) 30L),
                    "total", true,
                    "type", or(q.get("type"), "1"),
                    "area", q.get("area")), opt.withCrypto("weapi"));
                case "comment_hug_list" -> {
                    String type = Support.resourceType(or(q.get("type"), "0"));
                    yield client.request("/api/v2/resource/comments/hug/list", map(
                        "targetUserId", q.get("uid"),
                        "commentId", q.get("cid"),
                        "cursor", or(q.get("cursor"), "-1"),
                        "threadId", cat(type, q.get("sid")),
                        "pageNo", or(q.get("page"), (Object) 1L),
                        "idCursor", or(q.get("idCursor"), (Object) (-1L)),
                        "pageSize", or(q.get("pageSize"), (Object) 100L)), opt.withCrypto(""));
                }
                case "hug_comment" -> {
                    String type = Support.resourceType(or(q.get("type"), "0"));
                    yield client.request("/api/v2/resource/comments/hug/listener", map(
                        "targetUserId", q.get("uid"),
                        "commentId", q.get("cid"),
                        "threadId", cat(type, q.get("sid"))), opt.withCrypto(""));
                }
                case "comment_info_list" -> {
                    String raw = or(q.get("ids"), or(q.get("id"), ""));
                    List<String> ids = Arrays.stream(raw.split(",", -1)).map(String::trim).filter(s -> !s.isEmpty())
                        .toList();
                    yield client.request("/api/resource/commentInfo/list", map(
                        "resourceType", RESOURCE_TYPE_ID.get(or(q.get("type"), "0")),
                        "resourceIds", Js.json(ids)), opt.withCrypto("weapi"));
                }
                case "deviceinfo_center_upload" -> {
                    String deviceName = or(q.get("deviceName"), or(q.get("name"), "")).trim();
                    if (deviceName.isEmpty()) {
                        yield Mono.just(NcmResponse.error(400, "缺少必要参数: deviceName"));
                    }
                    yield client.request("/api/deviceinfo/center/upload", map("deviceName", deviceName),
                        opt.withCrypto("eapi"));
                }
                case "dj_program" -> client.request("/api/dj/program/byradio", map(
                    "radioId", q.get("rid"),
                    "limit", or(q.get("limit"), (Object) 30L),
                    "offset", or(q.get("offset"), (Object) 0L),
                    "asc", toBoolean(q.get("asc"))), opt.withCrypto("weapi"));
                case "dj_toplist" -> {
                    String type = or(q.get("type"), "new");
                    Object t = "hot".equals(type) ? (Object) 1L : "0";
                    yield client.request("/api/djradio/toplist", map(
                        "limit", or(q.get("limit"), (Object) 100L),
                        "offset", or(q.get("offset"), (Object) 0L),
                        "type", t), opt.withCrypto("weapi"));
                }
                case "event_privacy" -> eventPrivacy(q, opt);
                case "listentogether_sync_list_command" -> {
                    if (q.get("randomList") == null || q.get("displayList") == null) {
                        yield Mono.just(NcmResponse.message(400, "缺少参数: randomList, displayList"));
                    }
                    Map<String, Object> param = map(
                        "commandType", q.get("commandType"),
                        "version", List.of(map("userId", q.get("userId"), "version", q.get("version"))),
                        "anchorSongId", "",
                        "anchorPosition", -1L,
                        "randomList", Arrays.asList(q.get("randomList").split(",", -1)),
                        "displayList", Arrays.asList(q.get("displayList").split(",", -1)));
                    yield client.request("/api/listen/together/sync/list/command/report",
                        map("roomId", q.get("roomId"), "playlistParam", Js.json(param)), opt.withCrypto(""));
                }
                case "playlist_import_name_task_create" -> importTask(q, opt);
                case "playlist_subscribe" -> {
                    // 上游用 query.t === 1 判断是否附带 checkToken，HTTP 参数是字符串，所以永远不会附带
                    String path = "1".equals(q.get("t")) ? "subscribe" : "unsubscribe";
                    yield client.request("/api/playlist/" + path, map("id", q.get("id")),
                        opt.withCrypto("eapi").withCheckToken("v2"));
                }
                case "recommend_category_set" -> {
                    List<Object> tagIds = new ArrayList<>();
                    for (String s : Js.string(q.get("tags")).split(",", -1)) {
                        tagIds.add(Js.numeric(Js.number(s)));
                    }
                    String tags = Js.json(map("tagIds", tagIds, "categoryId", Js.numeric(Js.number(q.get("category")))));
                    yield client.request("/api/homepage/daily/song/tag/save", map("tags", tags), opt.withCrypto("xeapi"));
                }
                case "register_cellphone" -> {
                    if (q.get("password") == null) {
                        yield Mono.just(NcmResponse.error(400, "缺少参数: password"));
                    }
                    yield client.request("/api/w/register/cellphone", map(
                        "captcha", q.get("captcha"),
                        "phone", q.get("phone"),
                        "password", Support.md5(q.get("password")),
                        "nickname", q.get("nickname"),
                        "countrycode", or(q.get("countrycode"), "86"),
                        "force", "false"), opt.withCrypto(""));
                }
                case "user_bindingcellphone" -> client.request("/api/user/bindingCellphone", map(
                    "phone", q.get("phone"),
                    "countrycode", or(q.get("countrycode"), "86"),
                    "captcha", q.get("captcha"),
                    "password", blank(q.get("password")) ? "" : Support.md5(q.get("password"))), opt.withCrypto("weapi"));
                case "related_allvideo" -> client.request("/api/cloudvideo/v1/allvideo/rcmd", map(
                    "id", q.get("id"),
                    "type", q.get("id") != null && q.get("id").matches("\\d+") ? 0L : 1L), opt.withCrypto("weapi"));
                case "rep_ugc_exam_info_get" -> exam(q, opt, "/api/rep/ugc/exam/info/get", "examType");
                case "rep_ugc_exam_start" -> exam(q, opt, "/api/rep/ugc/exam/start", "examType");
                case "rep_ugc_exam_question_single_get" ->
                    exam(q, opt, "/api/rep/ugc/exam/question/single/get", "examType", "taskId");
                case "rep_ugc_exam_result_get" -> exam(q, opt, "/api/rep/ugc/exam/result/get", "examType", "taskId");
                case "rep_ugc_exam_submit" ->
                    exam(q, opt, "/api/rep/ugc/exam/submit", "examType", "taskId", "questionId", "answer");
                case "thinktank_audit_resource_update" -> {
                    if (blank(q.get("taskId")) || blank(q.get("judgement"))) {
                        yield Mono.just(NcmResponse.message(400, "参数不足"));
                    }
                    yield client.request("/api/thinktank/audit/resource/update", map(
                        "type", or(q.get("type"), "4"),
                        "taskId", q.get("taskId"),
                        "judgement", q.get("judgement")), opt.withCrypto("eapi"));
                }
                case "search_match" -> {
                    String songs = Js.json(List.of(map(
                        "title", or(q.get("title"), ""),
                        "album", or(q.get("album"), ""),
                        "artist", or(q.get("artist"), ""),
                        "duration", or(q.get("duration"), (Object) 0L),
                        "persistId", q.get("md5"))));
                    yield client.request("/api/search/match/new", map("songs", songs), opt.withCrypto(""));
                }
                case "song_wiki_info" -> {
                    String extJson = Js.json(map("states", map("playingResource",
                        map("current", q.get("id"), "scene", "songWiki"))));
                    yield client.request("/api/link/page/parent/relation/construct/info",
                        map("extJson", extJson, "positionCode", "songWikiMainPosition"), opt.withCrypto("eapi"));
                }
                case "summary_annual" -> {
                    String year = q.get("year");
                    String key = year != null && Set.of("2017", "2018", "2019").contains(year) ? "userdata" : "data";
                    yield client.request("/api/activity/summary/annual/" + Js.string(year) + "/" + key, Map.of(),
                        opt.withCrypto(""));
                }
                case "user_cloud_detail" -> {
                    if (q.get("id") == null) {
                        yield Mono.just(NcmResponse.error(400, "缺少参数: id"));
                    }
                    List<String> ids = Arrays.asList(q.get("id").replaceAll("\\s", "").split(",", -1));
                    yield client.request("/api/v1/cloud/get/byids", map("songIds", ids), opt.withCrypto("weapi"));
                }
                case "user_follow_mixed" -> {
                    Object size = or(q.get("size"), (Object) 30L);
                    Object cursor = or(q.get("cursor"), (Object) 0L);
                    yield client.request("/api/user/follow/users/mixed/get/v2", map(
                        "authority", "false",
                        "page", Js.json(map("size", size, "cursor", cursor)),
                        "scene", or(q.get("scene"), (Object) 0L),
                        "size", size,
                        "sortType", "0"), opt.withCrypto(""));
                }
                case "vip_timemachine" -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    if (!blank(q.get("startTime")) && !blank(q.get("endTime"))) {
                        data.put("startTime", q.get("startTime"));
                        data.put("endTime", q.get("endTime"));
                        data.put("type", 1L);
                        data.put("limit", or(q.get("limit"), (Object) 60L));
                    }
                    yield client.request("/api/vipmusic/newrecord/weekflow", data, opt.withCrypto("weapi"));
                }
                case "relay_play_state_submit" -> {
                    if (blank(q.get("id"))) {
                        yield Mono.just(NcmResponse.error(400, "缺少必要参数：id"));
                    }
                    double progress = Js.number(def(q.get("progress"), 0L));
                    String req = Js.json(map(
                        "resource", map("id", q.get("id"), "type", def(q.get("type"), "song")),
                        "progress", Double.isNaN(progress) ? 0L : Js.numeric(progress),
                        "sessionId", or(q.get("sessionId"), Support.randomChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 12)),
                        "playMode", def(q.get("playMode"), "list_loop")));
                    yield client.request("/api/relay/play/state/submit", map("playStateSubmitReq", req),
                        opt.withCrypto("weapi"));
                }
                case "song_download_url_v1" -> {
                    RequestOptions o = opt.withCrypto("");
                    if ("vivid".equals(q.get("level"))) {
                        o = o.withCookie(Support.asAndroid(opt.cookie()));
                    }
                    yield client.request("/api/song/enhance/download/url/v1",
                        map("id", q.get("id"), "immerseType", "c51", "level", q.get("level")), o);
                }
                case "song_url_ncmget" -> Mono.just(NcmResponse.ok(MAPPER.readTree("{\"code\":200,\"data\":[]}"), List.of()));
                case "login_refresh" -> client.request("/api/login/token/refresh", Map.of(), opt.withCrypto(""))
                    .map(r -> withCookieInBody(r));
                default -> Mono.just(NcmResponse.error(501, "接口 " + name + " 尚未移植"));
            };
        } catch (Exception e) {
            return Mono.just(NcmResponse.error(500, String.valueOf(e.getMessage())));
        }
    }

    Mono<NcmResponse> aidj(Map<String, String> q, RequestOptions opt) {
        Map<String, Object> ext = new LinkedHashMap<>();
        if (q.get("latitude") != null) {
            long sec = System.currentTimeMillis() / 1000;
            ext.put("lbsInfoList", List.of(map("lat", q.get("latitude"), "lon", q.get("longitude"), "time", sec)));
        }
        ext.put("noAidjToAidj", false);
        ext.put("lastRequestTimestamp", System.currentTimeMillis());
        ext.put("listenedTs", false);
        return client.request("/api/aidj/content/rcmd/info", map("extInfo", Js.json(ext)), opt.withCrypto(""));
    }

    /** isNaN(initial) ? (initial || '').toUpperCase().charCodeAt() || undefined : initial */
    static Object artistInitial(String initial) {
        if (Double.isNaN(Js.number(initial))) {
            String s = or(initial, "").toUpperCase();
            if (s.isEmpty()) {
                return null;
            }
            int code = s.charAt(0);
            return code == 0 ? null : (Object) (long) code;
        }
        return initial;
    }

    /** 对应 util/index.js 的 toBoolean。 */
    static Object toBoolean(String v) {
        if ("".equals(v)) {
            return "";
        }
        return "true".equals(v) || "1".equals(v);
    }

    Mono<NcmResponse> eventPrivacy(Map<String, String> q, RequestOptions opt) {
        String eventId = q.get("evId") == null ? "" : q.get("evId").trim();
        String raw = q.get("privacy") == null ? "" : q.get("privacy").trim();
        double p = Js.number(raw);
        if (eventId.isEmpty() || raw.isEmpty() || p != Math.rint(p) || Double.isNaN(p)
            || !PRIVACY_VALUES.contains((int) p)) {
            return Mono.just(NcmResponse.message(400, "evId is required and privacy must be one of 0, 1, 2, 6"));
        }
        return client.request("/api/event/privacy/op", map("eventId", eventId, "privacy", (long) p), opt.withCrypto(""));
    }

    Mono<NcmResponse> exam(Map<String, String> q, RequestOptions opt, String uri, String... required) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : required) {
            if (blank(q.get(k))) {
                return Mono.just(NcmResponse.message(400, "参数不足"));
            }
            data.put(k, q.get(k));
        }
        return client.request(uri, data, opt.withCrypto("eapi"));
    }

    Mono<NcmResponse> importTask(Map<String, String> q, RequestOptions opt) throws Exception {
        Map<String, Object> data = map("importStarPlaylist", or(q.get("importStarPlaylist"), (Object) false));
        if (!blank(q.get("local"))) {
            List<Object> multi = new ArrayList<>();
            for (JsonNode e : MAPPER.readTree(q.get("local"))) {
                multi.add(map("songName", text(e, "name"), "artistName", text(e, "artist"),
                    "albumName", text(e, "album")));
            }
            data.put("multiSongs", Js.json(multi));
        } else {
            String playlistName = or(q.get("playlistName"), "导入音乐 " + LocalDateTime.now().format(LOCALE_STRING));
            String songs = "";
            if (!blank(q.get("text"))) {
                songs = Js.json(List.of(map("name", playlistName, "type", "",
                    "url", Support.encodeURI("rpc://playlist/import?text=" + q.get("text")))));
            }
            if (!blank(q.get("link"))) {
                List<Object> list = new ArrayList<>();
                for (JsonNode e : MAPPER.readTree(q.get("link"))) {
                    list.add(map("name", playlistName, "type", "", "url", Support.encodeURI(e.asText())));
                }
                songs = Js.json(list);
            }
            data.put("playlistName", playlistName);
            data.put("taskIdForLog", "");
            data.put("songs", songs);
        }
        return client.request("/api/playlist/import/name/task/create", data, opt.withCrypto(""));
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 上游登录类接口：code=200 时把 Set-Cookie 拼进 body.cookie。 */
    static NcmResponse withCookieInBody(NcmResponse r) {
        if (r.bodyObject().path("code").asInt() != 200) {
            return r;
        }
        var body = r.bodyObject().deepCopy();
        body.put("cookie", String.join(";", r.cookies()));
        return new NcmResponse(200, body, r.cookies());
    }
}

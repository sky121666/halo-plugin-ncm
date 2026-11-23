package cn.sky.ncm.api;

import cn.sky.ncm.api.more.ToolApis;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.CheckTokenService;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 无法用描述表达的接口，按上游 module 逐个手写。 */
@Component
public class CustomApis {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Map<String, String> BANNER_TYPES = Map.of("0", "pc", "1", "android", "2", "iphone", "3", "ipad");

    private final NcmClient client;
    private final NcmBootstrap bootstrap;
    private final CheckTokenService checkTokens;
    private final Map<String, ApiGroup> groups = new HashMap<>();

    public CustomApis(NcmClient client, NcmBootstrap bootstrap, CheckTokenService checkTokens, List<ApiGroup> groupList) {
        this.client = client;
        this.bootstrap = bootstrap;
        this.checkTokens = checkTokens;
        for (ApiGroup g : groupList) {
            g.names().forEach(n -> groups.put(n, g));
        }
    }

    public Mono<NcmResponse> handle(String name, Map<String, String> q, RequestOptions opt) {
        return handle(name, q, Map.of(), opt);
    }

    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        ApiGroup group = groups.get(name);
        if (group != null) {
            return group.handle(name, q, files == null ? Map.of() : files, opt);
        }
        return switch (name) {
            case "login_qr_key" -> loginQrKey(opt);
            case "login_qr_create" -> Mono.just(loginQrCreate(q));
            case "login_qr_check" -> loginQrCheck(q.get("key"), opt);
            case "login_status" -> loginStatus(opt);
            case "logout" -> client.request("/api/logout", Map.of(), opt.withCrypto(""));
            case "song_url" -> songUrl(q, opt);
            case "song_url_v1" -> ToolApis.requestPlaybackUrl(client, q, opt);
            case "check_music" -> checkMusic(q, opt);
            case "song_detail" -> songDetail(q.get("ids"), opt.withCrypto("weapi"));
            case "playlist_track_all" -> playlistTrackAll(q, opt);
            case "search" -> search(q, opt);
            case "search_suggest" -> client.request("/api/search/suggest/" + ("mobile".equals(q.get("type")) ? "keyword" : "web"),
                map("s", or(q.get("keywords"), "")), opt.withCrypto("weapi"));
            case "banner" -> client.request("/api/v2/banner/get",
                map("clientType", BANNER_TYPES.getOrDefault(or(q.get("type"), "0"), "pc")), opt.withCrypto(""));
            case "top_album" -> topAlbum(q, opt);
            case "top_list" -> q.get("idx") != null && !q.get("idx").isEmpty()
                ? Mono.just(NcmResponse.error(500, "不支持此方式调用,只支持id调用"))
                : client.request("/api/playlist/v4/detail", map("id", q.get("id"), "n", "500", "s", "0"), opt.withCrypto(""));
            case "comment_new" -> commentNew(q, opt);
            case "comment" -> comment(q, opt);
            case "comment_like" -> likeResource(q, opt, "/api/v1/comment/", true);
            case "resource_like" -> likeResource(q, opt, "/api/resource/", false);
            case "song_like" -> client.request("/api/song/like",
                map("trackId", q.get("id"), "userid", q.get("uid"), "like", !"false".equals(q.get("like"))), opt.withCrypto(""));
            case "playlist_track_add" -> playlistTracks("/api/playlist/track/add", q.get("pid"), q, opt);
            case "playlist_track_delete" -> playlistTracks("/api/playlist/track/delete", q.get("id"), q, opt);
            case "register_anonimous" -> bootstrap.registerAnonymous().map(b -> new NcmResponse(200, b, List.of()));
            case "register_xeapikey" -> bootstrap.refresh().map(b -> new NcmResponse(200, b, List.of()));
            case "register_checktoken_v3" -> checkTokens.v3().map(t -> {
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.put("token", t);
                b.put("registered", !t.isEmpty());
                return new NcmResponse(200, b, List.of());
            });
            default -> Mono.just(NcmResponse.error(501, "接口 " + name + " 尚未移植"));
        };
    }

    /** module/login_qr_key.js */
    public Mono<NcmResponse> loginQrKey(RequestOptions opt) {
        return client.request("/api/login/qrcode/unikey", map("type", 3), opt.withCrypto(""))
            .map(r -> {
                ObjectNode body = JSON.objectNode();
                body.set("data", r.body());
                body.put("code", 200);
                return new NcmResponse(200, body, r.cookies());
            });
    }

    /** module/login_qr_create.js（不生成图片，qrimg 留空，由前端自行渲染二维码） */
    public NcmResponse loginQrCreate(Map<String, String> q) {
        String url = "https://music.163.com/login?codekey=" + q.get("key");
        if ("web".equals(q.get("platform"))) {
            url += "&chainId=v1_unknown-" + (int) (Math.random() * 1e6) + "_web_login_" + System.currentTimeMillis();
        }
        ObjectNode data = JSON.objectNode();
        data.put("qrurl", url);
        data.put("qrimg", "");
        ObjectNode body = JSON.objectNode();
        body.put("code", 200);
        body.set("data", data);
        return new NcmResponse(200, body, List.of());
    }

    /** module/login_qr_check.js：800 过期 / 801 等待扫码 / 802 待确认 / 803 成功 */
    public Mono<NcmResponse> loginQrCheck(String key, RequestOptions opt) {
        return client.request("/api/login/qrcode/client/login", map("key", key, "type", 3), opt.withCrypto(""))
            .map(r -> {
                ObjectNode body = r.body() instanceof ObjectNode on ? on.deepCopy() : JSON.objectNode();
                body.put("cookie", String.join(";", r.cookies()));
                return new NcmResponse(200, body, r.cookies());
            });
    }

    /** module/login_status.js */
    public Mono<NcmResponse> loginStatus(RequestOptions opt) {
        return client.request("/api/w/nuser/account/get", Map.of(), opt.withCrypto("weapi"))
            .map(r -> {
                if (r.body().path("code").asInt() != 200) {
                    return r;
                }
                ObjectNode body = JSON.objectNode();
                body.set("data", r.body());
                return new NcmResponse(200, body, r.cookies());
            });
    }

    /** module/song_url.js（eapi 版播放链接） */
    Mono<NcmResponse> songUrl(Map<String, String> q, RequestOptions opt) {
        List<String> ids = Arrays.asList(String.valueOf(q.get("id")).split(","));
        Object br = ExprEval.parseInt(or(q.get("br"), "999000"));
        if (br instanceof Double) {
            br = 999000L;
        }
        Map<String, Object> data = map("ids", NcmClient.toJson(ids), "br", br);
        return client.request("/api/song/enhance/player/url", data, opt.withCrypto(""))
            .map(r -> sortById(r, ids));
    }

    /** module/check_music.js */
    Mono<NcmResponse> checkMusic(Map<String, String> q, RequestOptions opt) {
        Object id = ExprEval.parseInt(q.get("id"));
        Object br = ExprEval.parseInt(or(q.get("br"), "999000"));
        Map<String, Object> data = map("ids", "[" + Js.string(id) + "]", "br", br);
        return client.request("/api/song/enhance/player/url", data, opt.withCrypto("weapi")).map(r -> {
            boolean playable = r.body().path("code").asInt() == 200
                && r.body().path("data").path(0).path("code").asInt() == 200;
            ObjectNode body = JSON.objectNode();
            body.put("code", 200);
            body.put("success", playable);
            body.put("message", playable ? "ok" : "亲爱的,暂无版权");
            return new NcmResponse(200, body, r.cookies());
        });
    }

    /** module/song_detail.js */
    Mono<NcmResponse> songDetail(String ids, RequestOptions opt) {
        String c = "[" + Arrays.stream(String.valueOf(ids).split("\\s*,\\s*"))
            .map(id -> "{\"id\":" + id + "}").collect(Collectors.joining(",")) + "]";
        return client.request("/api/v3/song/detail", map("c", c), opt);
    }

    /** module/playlist_track_all.js：先取歌单 trackIds，再批量取歌曲详情。 */
    Mono<NcmResponse> playlistTrackAll(Map<String, String> q, RequestOptions opt) {
        Object limitV = ExprEval.parseInt(q.get("limit"));
        Object offsetV = ExprEval.parseInt(q.get("offset"));
        int limit = limitV instanceof Long l && l != 0 ? l.intValue() : 1000;
        int offset = offsetV instanceof Long l ? l.intValue() : 0;
        Map<String, Object> data = map("id", q.get("id"), "n", 100000, "s", or(q.get("s"), "8"));
        RequestOptions o = opt.withCrypto("");
        return client.request("/api/v6/playlist/detail", data, o).flatMap(r -> {
            JsonNode trackIds = r.body().path("playlist").path("trackIds");
            if (!trackIds.isArray()) {
                return Mono.just(r);
            }
            List<String> ids = new ArrayList<>();
            for (int i = Math.max(0, offset); i < Math.min(trackIds.size(), offset + limit); i++) {
                ids.add(trackIds.get(i).path("id").asText());
            }
            return songDetail(String.join(",", ids), o);
        });
    }

    /** module/search.js */
    Mono<NcmResponse> search(Map<String, String> q, RequestOptions opt) {
        Object limit = or(q.get("limit"), null) != null ? q.get("limit") : (Object) 30;
        Object offset = or(q.get("offset"), null) != null ? q.get("offset") : (Object) 0;
        if ("2000".equals(q.get("type"))) {
            return client.request("/api/search/voice/get",
                map("keyword", q.get("keywords"), "scene", "normal", "limit", limit, "offset", offset), opt.withCrypto(""));
        }
        Object type = or(q.get("type"), null) != null ? q.get("type") : (Object) 1;
        return client.request("/api/search/get",
            map("s", q.get("keywords"), "type", type, "limit", limit, "offset", offset), opt.withCrypto(""));
    }

    /** module/top_album.js */
    Mono<NcmResponse> topAlbum(Map<String, String> q, RequestOptions opt) {
        LocalDate now = LocalDate.now();
        Map<String, Object> data = map(
            "area", or(q.get("area"), "ALL"),
            "limit", q.get("limit") != null && !q.get("limit").isEmpty() ? q.get("limit") : (Object) 50,
            "offset", q.get("offset") != null && !q.get("offset").isEmpty() ? q.get("offset") : (Object) 0,
            "type", or(q.get("type"), "new"),
            "year", q.get("year") != null && !q.get("year").isEmpty() ? q.get("year") : (Object) (long) now.getYear(),
            "month", q.get("month") != null && !q.get("month").isEmpty() ? q.get("month") : (Object) (long) now.getMonthValue(),
            "total", false,
            "rcmd", true);
        return client.request("/api/discovery/new/albums/area", data, opt.withCrypto("weapi"));
    }

    /** module/comment_new.js */
    Mono<NcmResponse> commentNew(Map<String, String> q, RequestOptions opt) {
        String type = ExprEval.RESOURCE_TYPE_MAP.get(String.valueOf(q.get("type")));
        String threadId = (type == null ? "undefined" : type) + q.get("id");
        Object pageSize = q.get("pageSize") != null && !q.get("pageSize").isEmpty() ? q.get("pageSize") : (Object) 20L;
        Object pageNo = q.get("pageNo") != null && !q.get("pageNo").isEmpty() ? q.get("pageNo") : (Object) 1L;
        double st = Js.number(q.get("sortType"));
        long sortType = Double.isNaN(st) || st == 0 ? 99 : (long) st;
        if (sortType == 1) {
            sortType = 99;
        }
        double ps = Js.number(pageSize);
        double pn = Js.number(pageNo);
        Object cursor = switch ((int) sortType) {
            case 99 -> Js.numeric((pn - 1) * ps);
            case 2 -> "normalHot#" + Js.string(Js.numeric((pn - 1) * ps));
            case 3 -> or(q.get("cursor"), "0");
            default -> "";
        };
        Object showInner = q.get("showInner") != null && !q.get("showInner").isEmpty() ? q.get("showInner") : (Object) true;
        Map<String, Object> data = map("threadId", threadId, "pageNo", pageNo, "showInner", showInner,
            "pageSize", pageSize, "cursor", cursor, "sortType", sortType);
        return client.request("/api/v2/resource/comments", data, opt.withCrypto(""));
    }

    /** module/comment.js：t=1 发送 / 0 删除 / 2 回复（eapi + 反作弊 token v2） */
    Mono<NcmResponse> comment(Map<String, String> q, RequestOptions opt) {
        String t = Map.of("1", "add", "0", "delete", "2", "reply").get(String.valueOf(q.get("t")));
        String type = ExprEval.RESOURCE_TYPE_MAP.get(String.valueOf(q.get("type")));
        Map<String, Object> data = map("threadId", (type == null ? "undefined" : type) + q.get("id"));
        if ("A_EV_2_".equals(type)) {
            data.put("threadId", q.get("threadId"));
        }
        if ("add".equals(t)) {
            putIf(data, "content", q.get("content"));
        } else if ("delete".equals(t)) {
            putIf(data, "commentId", q.get("commentId"));
        } else if ("reply".equals(t)) {
            putIf(data, "commentId", q.get("commentId"));
            putIf(data, "content", q.get("content"));
        }
        return client.request("/api/resource/comments/" + (t == null ? "undefined" : t), data,
            opt.withCrypto("eapi").withCheckToken("v2"));
    }

    /** module/comment_like.js、resource_like.js */
    Mono<NcmResponse> likeResource(Map<String, String> q, RequestOptions opt, String prefix, boolean withCid) {
        String t = "1".equals(q.get("t")) ? "like" : "unlike";
        String type = ExprEval.RESOURCE_TYPE_MAP.get(String.valueOf(q.get("type")));
        Map<String, Object> data = map("threadId", (type == null ? "undefined" : type) + q.get("id"));
        if (withCid) {
            putIf(data, "commentId", q.get("cid"));
        }
        if ("A_EV_2_".equals(type)) {
            data.put("threadId", q.get("threadId"));
        }
        return client.request(prefix + t, data, opt.withCrypto("weapi"));
    }

    /** module/playlist_track_add.js、playlist_track_delete.js */
    Mono<NcmResponse> playlistTracks(String uri, String playlistId, Map<String, String> q, RequestOptions opt) {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (String id : or(q.get("ids"), "").split(",", -1)) {
            tracks.add(map("type", 3, "id", id));
        }
        return client.request(uri, map("id", playlistId, "tracks", NcmClient.toJson(tracks)), opt.withCrypto("weapi"));
    }

    static NcmResponse sortById(NcmResponse r, List<String> ids) {
        JsonNode list = r.body().path("data");
        if (!list.isArray()) {
            return r;
        }
        List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        items.sort((a, b) -> Integer.compare(ids.indexOf(a.path("id").asText()), ids.indexOf(b.path("id").asText())));
        ArrayNode sorted = JSON.arrayNode().addAll(items);
        ObjectNode body = JSON.objectNode();
        body.put("code", 200);
        body.set("data", sorted);
        return new NcmResponse(200, body, r.cookies());
    }

    static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    static String or(String v, String fallback) {
        return v != null && !v.isEmpty() ? v : fallback;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}

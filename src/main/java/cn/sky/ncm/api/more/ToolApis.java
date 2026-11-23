package cn.sky.ncm.api.more;

import static cn.sky.ncm.api.more.Support.blank;
import static cn.sky.ncm.api.more.Support.map;
import static cn.sky.ncm.api.more.Support.or;

import cn.sky.ncm.api.ApiGroup;
import cn.sky.ncm.api.UploadFile;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.crypto.NcmCrypto;
import cn.sky.ncm.crypto.XeapiCrypto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 调试工具、通用透传，以及不走加密的直连接口。 */
@Component
public class ToolApis implements ApiGroup {

    /** 移植自的上游版本（inner_version 返回）。 */
    public static final String UPSTREAM_VERSION = "4.41.1";

    static final Pattern RELATED_PLAYLIST = Pattern.compile("<div class=\"cver u-cover u-cover-3\">[\\s\\S]*?<img src=\"([^\"]+)\">[\\s\\S]*?<a class=\"sname f-fs1 s-fc0\" href=\"([^\"]+)\"[^>]*>([^<]+?)</a>[\\s\\S]*?<a class=\"nm nm f-thide s-fc3\" href=\"([^\"]+)\"[^>]*>([^<]+?)</a>");
    static final String BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final NcmClient client;
    private final NcmHttp http;

    public ToolApis(NcmClient client, NcmHttp http) {
        this.client = client;
        this.http = http;
    }

    WebClient web() {
        return http.current();
    }

    @Override
    public Set<String> names() {
        return Set.of("api", "batch", "weblog", "decrypt", "eapi_decrypt", "inner_version", "audio_match",
            "related_playlist", "song_url_v1_302");
    }

    @Override
    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        try {
            return switch (name) {
                case "api" -> api(q, opt);
                case "batch" -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    q.forEach((k, v) -> {
                        if (k.startsWith("/api/")) {
                            data.put(k, v);
                        }
                    });
                    yield client.request("/api/batch", data, opt.withCrypto(""));
                }
                case "weblog" -> client.request("/api/feedback/weblog", jsonMap(q.get("data")), opt.withCrypto("weapi"));
                case "decrypt" -> Mono.just(decrypt(q));
                case "eapi_decrypt" -> Mono.just(eapiDecrypt(q));
                case "inner_version" -> {
                    ObjectNode b = JSON.objectNode();
                    b.put("code", 200);
                    b.putObject("data").put("version", UPSTREAM_VERSION);
                    yield Mono.just(NcmResponse.ok(b, List.of()));
                }
                case "audio_match" -> audioMatch(q);
                case "related_playlist" -> relatedPlaylist(q);
                case "song_url_v1_302" -> songUrlV1Redirect(q, opt);
                default -> Mono.just(NcmResponse.error(501, "接口 " + name + " 尚未移植"));
            };
        } catch (Exception e) {
            return Mono.just(NcmResponse.error(500, String.valueOf(e.getMessage())));
        }
    }

    /** module/api.js：任意接口透传（仅管理员可用）。 */
    Mono<NcmResponse> api(Map<String, String> q, RequestOptions opt) {
        String uri = q.get("uri");
        if (uri == null || !uri.startsWith("/api/")) {
            return Mono.just(NcmResponse.error(400, "uri 必须以 /api/ 开头"));
        }
        Map<String, Object> data = jsonMap(q.get("data"));
        RequestOptions o = opt.withCrypto(or(q.get("crypto"), ""));
        if (data.get("cookie") instanceof String c) {
            Map<String, String> cookie = cn.sky.ncm.account.AccountService.parseCookie(c);
            data.put("cookie", cookie);
            o = o.withCookie(cookie);
        }
        return client.request(uri, data, o);
    }

    static Map<String, Object> jsonMap(String json) {
        if (blank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** module/decrypt.js */
    static NcmResponse decrypt(Map<String, String> q) {
        String crypto = or(q.get("crypto"), "eapi");
        String data = or(q.get("data"), or(q.get("hexString"), ""));
        boolean isReq = !"false".equals(q.get("isReq"));
        if (data.isEmpty()) {
            return NcmResponse.message(400, "data is required");
        }
        try {
            JsonNode result;
            switch (crypto) {
                case "eapi" -> {
                    String hex = data.replaceAll("\\s", "");
                    result = isReq ? reqParts(hex) : resJson(hex);
                }
                case "weapi" -> {
                    if (isReq) {
                        return NcmResponse.message(400,
                            "weapi 请求解密需要 RSA 私钥，暂不支持；仅支持 weapi 返回数据解密（e_r=true 时与 eapi 相同）");
                    }
                    result = resJson(data.replaceAll("\\s", ""));
                }
                case "linuxapi" -> result = isReq
                    ? MAPPER.readTree(NcmCrypto.linuxapiDecryptHex(data.replaceAll("\\s", "")))
                    : MAPPER.readTree(data);
                case "xeapi" -> {
                    if (isReq) {
                        return NcmResponse.message(400,
                            "xeapi 请求解密涉及 X25519 ECDH 密钥交换，流程复杂，暂不支持；仅支持 xeapi 返回数据解密");
                    }
                    result = MAPPER.readTree(XeapiCrypto.decryptResponse(Base64.getDecoder().decode(data)));
                }
                case "api" -> result = MAPPER.readTree(data);
                default -> {
                    return NcmResponse.message(400, "未知加密方式: " + crypto);
                }
            }
            ObjectNode b = JSON.objectNode();
            b.put("code", 200);
            b.set("data", result);
            return NcmResponse.ok(b, List.of());
        } catch (Exception e) {
            return NcmResponse.message(400, "解密失败: " + e.getMessage());
        }
    }

    /** module/eapi_decrypt.js */
    static NcmResponse eapiDecrypt(Map<String, String> q) {
        String hex = q.get("hexString");
        if (blank(hex)) {
            return NcmResponse.message(400, "hex string is required");
        }
        String pure = hex.replaceAll("\\s", "");
        ObjectNode b = JSON.objectNode();
        b.put("code", 200);
        try {
            b.set("data", !"false".equals(q.get("isReq")) ? reqParts(pure) : resJson(pure));
        } catch (Exception e) {
            b.putNull("data");
        }
        return NcmResponse.ok(b, List.of());
    }

    static JsonNode reqParts(String hex) throws Exception {
        String[] parts = NcmCrypto.eapiReqDecryptParts(hex);
        if (parts == null) {
            return JSON.nullNode();
        }
        ObjectNode n = JSON.objectNode();
        n.put("url", parts[0]);
        n.set("data", MAPPER.readTree(parts[1]));
        return n;
    }

    /** 上游 eapiResDecrypt 出错时返回 null。 */
    static JsonNode resJson(String hex) {
        try {
            return MAPPER.readTree(NcmCrypto.eapiResDecryptHex(hex, false));
        } catch (Exception e) {
            return JSON.nullNode();
        }
    }

    /** module/audio_match.js：听歌识曲（audioFP 由前端生成）。 */
    Mono<NcmResponse> audioMatch(Map<String, String> q) {
        String url = "https://interface.music.163.com/api/music/audio/match?sessionId=0123456789abcdef"
            + "&algorithmCode=shazam_v2&duration=" + Js.string(q.get("duration"))
            + "&rawdata=" + NcmClient.encodeURIComponent(Js.string(q.get("audioFP"))) + "&times=1&decrypt=1";
        return web().get().uri(java.net.URI.create(url)).retrieve().bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
            .map(text -> {
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                try {
                    JsonNode d = MAPPER.readTree(text).get("data");
                    if (d != null) {
                        b.set("data", d);
                    }
                } catch (Exception e) {
                    b.putNull("data");
                }
                return NcmResponse.ok(b, List.of());
            })
            .onErrorResume(e -> Mono.just(NcmResponse.error(502, String.valueOf(e.getMessage()))));
    }

    /** module/related_playlist.js：抓网页版歌单页的“相关推荐”。 */
    Mono<NcmResponse> relatedPlaylist(Map<String, String> q) {
        return web().get().uri(java.net.URI.create("https://music.163.com/playlist?id=" + Js.string(q.get("id"))))
            .header("User-Agent", BROWSER_UA)
            .retrieve().bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
            .map(html -> {
                ArrayNode list = JSON.arrayNode();
                Matcher m = RELATED_PLAYLIST.matcher(html);
                while (m.find()) {
                    ObjectNode p = list.addObject();
                    ObjectNode creator = p.putObject("creator");
                    creator.put("userId", cut(m.group(4), "/user/home?id=".length(), 0));
                    creator.put("nickname", m.group(5));
                    p.put("coverImgUrl", cut(m.group(1), 0, "?param=50y50".length()));
                    p.put("name", m.group(3));
                    p.put("id", cut(m.group(2), "/playlist?id=".length(), 0));
                }
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.set("playlists", list);
                return NcmResponse.ok(b, List.of());
            })
            .onErrorResume(e -> Mono.just(NcmResponse.error(500, String.valueOf(e.getMessage()))));
    }

    static String cut(String s, int head, int tail) {
        int end = Math.max(head, s.length() - tail);
        return head >= s.length() ? "" : s.substring(head, end);
    }

    /**
     * 取址结果。
     *
     * @param response 最后一次请求网易云的响应（失败时用它判断原因）
     * @param url      音频地址（已改成 https），没有时为 null
     * @param item     音频信息节点（含 freeTrialInfo、fee、br、level、expi 等），可能是 MissingNode
     */
    public record SongUrl(NcmResponse response, String url, JsonNode item) {
        public int responseCode() {
            return response == null ? 0 : response.bodyObject().path("code").asInt(response.status());
        }

        /** 取址和歌单抽查共用判定。部分上游业务错误已被客户端归一为 HTTP 200。 */
        public SongAvailability availability() {
            if (response == null) {
                return SongAvailability.SERVICE;
            }
            int status = response.status();
            int code = responseCode();
            if (status == 429 || code == 429) {
                return SongAvailability.RATE_LIMITED;
            }
            if (code == 404 && (status == 200 || status == 404)) {
                return SongAvailability.UNAVAILABLE;
            }
            if (status != 200 || code != 200 || item == null || !item.isObject()) {
                return SongAvailability.SERVICE;
            }
            int songCode = item.path("code").asInt(200);
            if (songCode == 429) {
                return SongAvailability.RATE_LIMITED;
            }
            if (songCode == 404) {
                return SongAvailability.UNAVAILABLE;
            }
            if (songCode != 200) {
                return SongAvailability.SERVICE;
            }
            if (!blank(url)) {
                JsonNode trial = item.path("freeTrialInfo");
                return trial.isObject() && trial.path("end").asDouble(0) > 0
                    ? SongAvailability.TRIAL : SongAvailability.FULL;
            }
            JsonNode location = item.path("url");
            return location.isNull() || (location.isTextual()
                && (location.asText().isBlank() || "null".equals(location.asText())))
                ? SongAvailability.UNAVAILABLE : SongAvailability.SERVICE;
        }
    }

    public enum SongAvailability {
        FULL, TRIAL, UNAVAILABLE, RATE_LIMITED, SERVICE
    }

    /** module/song_url_v1_302.js：直接 302 跳转到音频地址。 */
    Mono<NcmResponse> songUrlV1Redirect(Map<String, String> q, RequestOptions opt) {
        return resolveSongUrl(q, opt).map(s -> switch (s.availability()) {
            case FULL, TRIAL -> NcmResponse.redirect(s.url(), s.response().cookies());
            default -> s.response();
        });
    }

    /** module/song_url_v1.js（xeapi），默认 standard，失败时用 eapi 重试。 */
    public static Mono<NcmResponse> requestPlaybackUrl(NcmClient client, Map<String, String> query,
                                                        RequestOptions options) {
        if ("true".equals(query.get("unblock"))) {
            return Mono.just(NcmResponse.error(501, "暂不支持 unblock 解灰"));
        }
        String level = or(query.get("level"), "standard");
        boolean vivid = "vivid".equals(level);
        Map<String, Object> data = map("ids", "[" + query.get("id") + "]", "level", level,
            "encodeType", vivid ? "mp3" : "flac");
        if ("sky".equals(level)) {
            data.put("immerseType", or(query.get("immerseType"), "c51"));
        }
        RequestOptions playbackOptions = options.withCrypto("xeapi");
        final RequestOptions request = vivid
            ? playbackOptions.withCookie(Support.asAndroid(options.cookie())) : playbackOptions;
        String uri = "/api/song/enhance/player/url/v1";
        return client.request(uri, data, request)
            .flatMap(response -> response.ok() ? Mono.just(response)
                : client.request(uri, data, request.withCrypto("")).map(ToolApis::markPlaybackFallback));
    }

    private static NcmResponse markPlaybackFallback(NcmResponse response) {
        if (response.body() instanceof ObjectNode object) {
            ObjectNode copy = object.deepCopy();
            copy.put("fallback", "eapi");
            return response.withBody(copy);
        }
        return response;
    }

    public Mono<SongUrl> resolveSongUrl(Map<String, String> q, RequestOptions opt) {
        return requestPlaybackUrl(client, q, opt).map(response -> {
            JsonNode item = response.bodyObject().path("data").path(0);
            JsonNode location = item.path("url");
            String url = location.isTextual() ? location.asText() : null;
            String audio = response.ok() && !blank(url) && !"null".equals(url) ? httpsAudio(url) : null;
            return new SongUrl(response, audio, item);
        });
    }

    /** 网易云音频 CDN 支持 https：把 http 地址改成 https，避免 https 站点播放时出现混合内容。 */
    public static String httpsAudio(String url) {
        if (url != null && url.startsWith("http://")) {
            try {
                String host = java.net.URI.create(url).getHost();
                if (host != null && (host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))) {
                    return "https://" + url.substring("http://".length());
                }
            } catch (IllegalArgumentException ignored) {
                return url;
            }
        }
        return url;
    }

}

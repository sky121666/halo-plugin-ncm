package cn.sky.ncm.api.more;

import static cn.sky.ncm.api.more.Support.map;
import static cn.sky.ncm.api.more.Support.or;

import cn.sky.ncm.api.ApiGroup;
import cn.sky.ncm.api.UploadFile;
import cn.sky.ncm.client.Js;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.crypto.Ncbl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** module/scrobble_v1.js：模拟 PC 客户端的加密日志上报听歌记录（PLV 开始播放 + PLD 播放结束）。 */
@Component
public class ScrobbleV1Api implements ApiGroup {

    static final String UPLOAD_URL = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true";
    static final char SEP = '\u0001';

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final NcmClient client;
    private final NcmHttp http;

    public ScrobbleV1Api(NcmClient client, NcmHttp http) {
        this.client = client;
        this.http = http;
    }

    WebClient web() {
        return http.current();
    }

    @Override
    public Set<String> names() {
        return Set.of("scrobble_v1");
    }

    record Ctx(Map<String, String> app, Map<String, String> device, Map<String, String> auth) {
    }

    @Override
    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        double songId = Js.number(q.get("id"));
        if (songId == 0 || Double.isNaN(songId)) {
            return Mono.just(NcmResponse.error(400, "缺少有效的 id (歌曲ID)"));
        }
        double playTime = Js.number(q.get("time"));
        if (Double.isNaN(playTime) || playTime <= 0) {
            return Mono.just(NcmResponse.error(400, "缺少有效的 time (播放时长)"));
        }
        double total = Js.number(q.get("total"));
        double totalTime = Double.isNaN(total) || total == 0 ? playTime : total;
        String sourceId = or(q.get("sourceid"), or(q.get("sourceId"), ""));
        String sourceName = or(q.get("source"), "list");

        Map<String, String> cookie = new LinkedHashMap<>(opt.cookieOrEmpty());
        cookie.put("os", "pc");
        cookie.putIfAbsent("deviceId", client.device().deviceId());
        Ctx ctx = context(cookie);
        if (ctx.auth().get("token").isEmpty()) {
            return Mono.just(NcmResponse.error(401, "缺少 MUSIC_U 鉴权令牌"));
        }
        double br = Js.number(q.get("bitrate"));
        Map<String, Object> song = map(
            "id", Js.numeric(songId),
            "bitrate", Double.isNaN(br) || br == 0 ? (Object) 320L : Js.numeric(br),
            "level", or(q.get("level"), "exhigh"),
            "time", Js.numeric(totalTime));
        String srcId = sourceId.isEmpty() ? Js.string(Js.numeric(songId)) : sourceId;
        long ts = System.currentTimeMillis() / 1000;
        Object played = Js.numeric(Math.min(playTime, totalTime));

        String meta = metaJson(ctx);
        String cookieStr = cookieStr(ctx);
        String plv = logRecord(ts, "_plv", plv(ctx, song, srcId, sourceName));
        String pld = logRecord(ts, "_pld", pld(ctx, song, srcId, sourceName, played));

        return upload(ctx, meta, plv, cookieStr).flatMap(r1 -> {
            if (!r1.success) {
                String rate = r1.body.path("data").hasNonNull("rate") ? " (rate=" + r1.body.path("data").get("rate").asText() + ")" : "";
                return Mono.just(result(r1.body.path("code").asInt(-1) == 0 ? -1 : r1.body.path("code").asInt(-1),
                    "PLV 上报失败" + rate, r1.body));
            }
            return upload(ctx, meta, pld, cookieStr).map(r2 -> {
                if (!r2.success) {
                    ObjectNode d = JSON.objectNode();
                    d.set("plv", r1.body);
                    d.set("pld", r2.body);
                    return result(r2.body.path("code").asInt(-1) == 0 ? -1 : r2.body.path("code").asInt(-1),
                        "PLV 成功但 PLD 失败", d);
                }
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.put("data", "scrobble_v1 上报成功");
                ObjectNode d = b.putObject("details");
                d.putObject("plv").put("fileName", r1.fileName).put("payloadSize", r1.payloadSize);
                d.putObject("pld").put("fileName", r2.fileName).put("payloadSize", r2.payloadSize);
                return NcmResponse.ok(b, List.of());
            });
        }).onErrorResume(e -> Mono.just(NcmResponse.error(502, "请求异常: " + e.getMessage())));
    }

    static NcmResponse result(int code, String msg, JsonNode details) {
        ObjectNode b = JSON.objectNode();
        b.put("code", code);
        b.put("msg", msg);
        b.set("details", details);
        return NcmResponse.ok(b, List.of());
    }

    static Ctx context(Map<String, String> c) {
        Map<String, String> app = new LinkedHashMap<>();
        app.put("nsm", or(c.get("WEVNSM"), "1.0.0"));
        app.put("cid", or(c.get("WNMCID"), HexFormat.of().formatHex(randomBytes(3)) + "." + System.currentTimeMillis() + ".01.0"));
        app.put("channel", or(c.get("channel"), "netease"));
        app.put("version", or(c.get("appver"), "3.1.35"));
        app.put("versionCode", or(c.get("versioncode"), "205293"));
        Map<String, String> device = new LinkedHashMap<>();
        device.put("id", or(c.get("deviceId"), or(c.get("sDeviceId"), "")));
        device.put("ti", or(c.get("NMTID"), ""));
        device.put("sign", or(c.get("clientSign"), ""));
        device.put("model", or(c.get("mode"), or(c.get("mobilename"), "")));
        device.put("nnid", or(c.get("_ntes_nnid"), ","));
        device.put("nuid", or(c.get("_ntes_nuid"), ""));
        device.put("csrf", or(c.get("__csrf"), ""));
        device.put("systemType", or(c.get("os"), "pc"));
        device.put("systemVersion", or(c.get("osver"), "Microsoft-Windows-10-Professional-build-19045-64bit"));
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("token", or(c.get("MUSIC_U"), ""));
        auth.put("sessionId", or(c.get("JSESSIONID-WYYY"), ""));
        auth.put("vipType", or(c.get("vipType"), ""));
        return new Ctx(app, device, auth);
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        Support.RANDOM.nextBytes(b);
        return b;
    }

    static String appver(Ctx ctx) {
        return ctx.app().get("version") + "." + ctx.app().get("versionCode");
    }

    static String metaJson(Ctx ctx) {
        return Js.json(map(
            "JSESSIONID-WYYY", ctx.auth().get("sessionId"),
            "MUSIC_U", ctx.auth().get("token"),
            "NMTID", ctx.device().get("ti"),
            "WEVNSM", ctx.app().get("nsm"),
            "WNMCID", ctx.app().get("cid"),
            "__csrf", ctx.device().get("csrf"),
            "_iuqxldmzr_", "33",
            "_ntes_nnid", ctx.device().get("nnid"),
            "_ntes_nuid", ctx.device().get("nuid"),
            "appver", appver(ctx),
            "channel", ctx.app().get("channel"),
            "clientSign", ctx.device().get("sign"),
            "deviceId", ctx.device().get("id"),
            "mode", ctx.device().get("model"),
            "ntes_kaola_ad", "1",
            "os", ctx.device().get("systemType"),
            "osver", ctx.device().get("systemVersion")));
    }

    static String cookieStr(Ctx ctx) {
        var j = new StringJoiner("; ");
        j.add("JSESSIONID-WYYY=" + ctx.auth().get("sessionId"));
        j.add("MUSIC_U=" + ctx.auth().get("token"));
        j.add("NMTID=" + ctx.device().get("ti"));
        j.add("WEVNSM=" + ctx.app().get("nsm"));
        j.add("WNMCID=" + ctx.app().get("cid"));
        j.add("__csrf=" + ctx.device().get("csrf"));
        j.add("__remember_me=true");
        j.add("_iuqxldmzr_=33");
        j.add("_ntes_nnid=" + ctx.device().get("nnid"));
        j.add("_ntes_nuid=" + ctx.device().get("nuid"));
        j.add("appver=" + appver(ctx));
        j.add("channel=" + ctx.app().get("channel"));
        j.add("clientSign=" + ctx.device().get("sign"));
        j.add("deviceId=" + ctx.device().get("id"));
        j.add("mode=" + ctx.device().get("model"));
        j.add("ntes_kaola_ad=1");
        j.add("os=" + ctx.device().get("systemType"));
        j.add("osver=" + ctx.device().get("systemVersion"));
        return j.toString();
    }

    static String logRecord(long time, String action, Map<String, Object> data) {
        return time + String.valueOf(SEP) + action + SEP + Js.json(data);
    }

    static Map<String, Object> plv(Ctx ctx, Map<String, Object> song, String srcId, String srcName) {
        long now = System.currentTimeMillis();
        String v = ctx.app().get("version");
        String vc = ctx.app().get("versionCode");
        String id = Js.string(song.get("id"));
        String addRefer = "[F:63][" + now + "#933#" + v + "#" + vc + "#c9156c3][e][2][23][cell_pc_songlist_song:2|"
            + "page_pc_songlist_songflow|page_mine_like_music][" + id + ":song:x:x|:::|" + srcId + ":list::]";
        List<String> multi = List.of("[F:26][s][18][_ai]", "[F:26][s][12][_ai]",
            "[F:63][" + now + "#933#" + v + "#" + vc + "#c9156c3][e][2][8][cell_pc_main_tab_entrance:6|page_pc_main_tab]"
                + "[我喜欢的音乐:spm::|:::]",
            "[F:26][s][5][_ai]", "[F:26][s][0][_ai]");
        return map("mode", "circulation", "download", 0L, "alg", "", "status", "front", "id", id,
            "bitrate", song.get("bitrate"), "type", "song", "is_listentogether", 0L, "source", srcName, "is_heart", 0L,
            "resource_ratio", "", "resource_time", song.get("time"), "musiceffect_id", "", "app_mode", 2L,
            "bitrate_level", song.get("level"), "_addrefer", addRefer, "_multirefers", multi,
            "vipType", ctx.auth().get("vipType"), "fee", 1L, "file", 4L, "rightSource", 0L, "sourceId", srcId,
            "sourcetype", "track", "libra_abt", "", "channel", ctx.app().get("channel"), "curStartChannel", "");
    }

    static Map<String, Object> pld(Ctx ctx, Map<String, Object> song, String srcId, String srcName, Object played) {
        long now = System.currentTimeMillis();
        String v = ctx.app().get("version");
        String vc = ctx.app().get("versionCode");
        String id = Js.string(song.get("id"));
        String addRefer = "[F:63][" + now + "#616#" + v + "#" + vc + "#c9156c3][e][2][92][btn_pc_cover_play|"
            + "cell_pc_songlist_song:6|page_pc_songlist_songflow|page_mine_like_music][:::|" + id + ":song:x:x|:::|"
            + srcId + ":list::]";
        List<String> multi = List.of("[F:26][s][87][_ai]", "[F:26][s][81][_ai]", "[F:26][s][75][_ai]",
            "[F:26][s][69][_ai]", "[F:26][s][63][_ai]");
        return map("mode", "circulation", "download", 0L, "alg", "", "status", "front", "id", id, "time", played,
            "type", "song", "is_listentogether", 0L, "source", srcName, "is_heart", 0L, "realtime", played,
            "resource_ratio", "", "resource_time", song.get("time"), "musiceffect_id", "1001", "app_mode", 1L,
            "lyriceffect", "default", "displayMode", "classic", "bitrate", song.get("bitrate"),
            "bitrate_level", song.get("level"), "_addrefer", addRefer, "_multirefers", multi,
            "vipType", ctx.auth().get("vipType"), "fee", 8L, "file", 4L, "rightSource", 0L, "sourceId", srcId,
            "sourcetype", "track", "end", "interrupt", "libra_abt", "", "channel", ctx.app().get("channel"),
            "curStartChannel", "");
    }

    record UploadResult(boolean success, String fileName, int payloadSize, JsonNode body) {
    }

    Mono<UploadResult> upload(Ctx ctx, String meta, String body, String cookieStr) {
        byte[] payload = Ncbl.encrypt(meta, body);
        String boundary = UUID.randomUUID().toString().replace("-", "");
        String fileName = "op_" + (Support.RANDOM.nextInt(90000) + 10000) + "_0_"
            + (1 + (long) (Support.RANDOM.nextDouble() * 4294967295L));
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
            + "\"\r\nContent-Type: multipart/form-data\r\n\r\n";
        String foot = "\r\n--" + boundary + "--\r\n";
        byte[] h = head.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] f = foot.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] multipart = new byte[h.length + payload.length + f.length];
        System.arraycopy(h, 0, multipart, 0, h.length);
        System.arraycopy(payload, 0, multipart, h.length, payload.length);
        System.arraycopy(f, 0, multipart, h.length + payload.length, f.length);
        return web().post().uri(URI.create(UPLOAD_URL))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .header("Referer", "https://music.163.com/di")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/" + ctx.app().get("version"))
            .header("Accept-Language", "zh-CN,zh;q=0.8")
            .header("Cookie", cookieStr)
            .bodyValue(multipart)
            .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty(""))
            .timeout(Duration.ofSeconds(15))
            .map(text -> {
                JsonNode n;
                try {
                    n = MAPPER.readTree(text);
                } catch (Exception e) {
                    n = JSON.textNode(text);
                }
                boolean ok = n.path("code").asInt() == 200 && contains(n.path("data").path("successfiles"), fileName);
                return new UploadResult(ok, fileName, payload.length, n == null ? JSON.nullNode() : n);
            });
    }

    static boolean contains(JsonNode arr, String s) {
        if (arr.isArray()) {
            for (JsonNode x : arr) {
                if (s.equals(x.asText())) {
                    return true;
                }
            }
        }
        return arr.isTextual() && arr.asText().contains(s);
    }
}

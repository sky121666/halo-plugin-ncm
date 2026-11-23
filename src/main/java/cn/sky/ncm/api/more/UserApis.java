package cn.sky.ncm.api.more;

import static cn.sky.ncm.api.more.Support.blank;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 登录、签到、动态聚合、广告权益、歌单歌曲操作、听歌打卡。 */
@Component
public class UserApis implements ApiGroup {

    static final String CLIENTLOG_DOMAIN = "https://clientlog.music.163.com";
    static final int EVENT_PAGE_SIZE = 100;
    static final int EVENT_MAX_PAGES = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final NcmClient client;

    public UserApis(NcmClient client) {
        this.client = client;
    }

    @Override
    public Set<String> names() {
        return Set.of("login", "login_cellphone", "verify_getQr", "vip_sign", "user_event_all", "ad_get",
            "ad_listening_rights_gain", "playlist_tracks", "scrobble");
    }

    @Override
    public Mono<NcmResponse> handle(String name, Map<String, String> q, Map<String, UploadFile> files,
                                    RequestOptions opt) {
        return switch (name) {
            case "login" -> login(q, opt);
            case "login_cellphone" -> loginCellphone(q, opt);
            case "verify_getQr" -> verifyGetQr(q, opt);
            case "vip_sign" -> vipSign(opt);
            case "user_event_all" -> userEventAll(q, opt);
            case "ad_get" -> adGet(q, opt);
            case "ad_listening_rights_gain" -> adListeningRightsGain(q, opt);
            case "playlist_tracks" -> playlistTracks(q, opt);
            case "scrobble" -> scrobble(q, opt);
            default -> Mono.just(NcmResponse.error(501, "接口 " + name + " 尚未移植"));
        };
    }

    /** module/login.js（邮箱登录） */
    Mono<NcmResponse> login(Map<String, String> q, RequestOptions opt) {
        if (blank(q.get("md5_password")) && q.get("password") == null) {
            return Mono.just(NcmResponse.error(400, "缺少参数: password"));
        }
        Map<String, Object> data = map(
            "type", "0",
            "https", "true",
            "username", q.get("email"),
            "password", or(q.get("md5_password"), Support.md5(q.get("password"))),
            "rememberLogin", "true");
        return client.request("/api/w/login", data, opt.withCrypto("")).map(r -> {
            if (r.bodyObject().path("code").asInt() == 502) {
                ObjectNode b = JSON.objectNode();
                b.put("msg", "账号或密码错误");
                b.put("code", 502);
                b.put("message", "账号或密码错误");
                return NcmResponse.ok(b, List.of());
            }
            return loginResult(r);
        });
    }

    /** module/login_cellphone.js（手机号 + 密码 或 验证码） */
    public Mono<NcmResponse> loginCellphone(Map<String, String> q, RequestOptions opt) {
        boolean byCaptcha = !blank(q.get("captcha"));
        if (!byCaptcha && blank(q.get("md5_password")) && q.get("password") == null) {
            return Mono.just(NcmResponse.error(400, "缺少参数: password 或 captcha"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "1");
        data.put("https", "true");
        data.put("phone", q.get("phone"));
        data.put("countrycode", or(q.get("countrycode"), "86"));
        if (byCaptcha) {
            data.put("captcha", q.get("captcha"));
        } else {
            data.put("password", or(q.get("md5_password"), Support.md5(q.get("password"))));
        }
        data.put("remember", "true");
        data.put("secureCaptcha", or(q.get("sca"), ""));
        data.values().removeIf(v -> v == null);
        return client.request("/api/w/login/cellphone", data, opt.withCrypto("weapi")).map(UserApis::loginResult);
    }

    static NcmResponse loginResult(NcmResponse r) {
        if (r.bodyObject().path("code").asInt() != 200) {
            return r;
        }
        try {
            ObjectNode body = (ObjectNode) MAPPER.readTree(
                MAPPER.writeValueAsString(r.body()).replace("avatarImgId_str", "avatarImgIdStr"));
            body.put("cookie", String.join(";", r.cookies()));
            return new NcmResponse(200, body, r.cookies());
        } catch (Exception e) {
            return r;
        }
    }

    /** module/verify_getQr.js（不生成图片，qrimg 留空） */
    Mono<NcmResponse> verifyGetQr(Map<String, String> q, RequestOptions opt) {
        String params = Js.json(map("event_id", q.get("evid"), "sign", q.get("sign")));
        Map<String, Object> data = map(
            "verifyConfigId", q.get("vid"),
            "verifyType", q.get("type"),
            "token", q.get("token"),
            "params", params,
            "size", 150L);
        return client.request("/api/frontrisk/verify/getqrcode", data, opt.withCrypto("weapi")).map(r -> {
            JsonNode qr = r.bodyObject().path("data").path("qrCode");
            if (qr.isMissingNode()) {
                return r;
            }
            String url = "https://st.music.163.com/encrypt-pages?qrCode=" + qr.asText()
                + "&verifyToken=" + Js.string(q.get("token")) + "&verifyId=" + Js.string(q.get("vid"))
                + "&verifyType=" + Js.string(q.get("type")) + "&params=" + params;
            ObjectNode d = JSON.objectNode();
            d.set("qrCode", qr);
            d.put("qrurl", url);
            d.put("qrimg", "");
            ObjectNode b = JSON.objectNode();
            b.put("code", 200);
            b.set("data", d);
            return NcmResponse.ok(b, r.cookies());
        });
    }

    /** module/vip_sign.js：黑胶乐签打卡 */
    Mono<NcmResponse> vipSign(RequestOptions opt) {
        return client.request("/api/vip-center-bff/task/sign", Map.of(), opt.withCrypto("weapi")).flatMap(sign -> {
            if (!sign.ok()) {
                return Mono.just(sign);
            }
            return client.request("/api/vipnewcenter/app/level/user/checkin/history/detail",
                map("signDayTime", System.currentTimeMillis(), "type", 1L), opt.withCrypto("eapi")).map(detail -> {
                    if (!detail.ok()) {
                        return detail;
                    }
                    boolean signed = sign.bodyObject().path("code").asInt() == 200
                        && detail.bodyObject().path("code").asInt() == 200;
                    ObjectNode b = JSON.objectNode();
                    b.put("code", 200);
                    b.set("taskSign", sign.body());
                    b.set("checkinDetail", detail.body());
                    b.put("signed", signed);
                    b.put("message", signed ? "黑胶乐签打卡成功" : "黑胶乐签打卡失败");
                    return NcmResponse.ok(b, sign.cookies());
                });
        });
    }

    /** module/user_event_all.js：翻完当前登录用户的全部动态。 */
    Mono<NcmResponse> userEventAll(Map<String, String> q, RequestOptions opt) {
        return client.request("/api/nuser/account/get", Map.of(), opt.withCrypto("weapi")).flatMap(acc -> {
            JsonNode b = acc.bodyObject();
            String uid = b.path("account").path("id").asText(b.path("profile").path("userId").asText(""));
            if (uid.isEmpty() || "0".equals(uid)) {
                if (!acc.ok() || (b.has("code") && b.path("code").asInt() != 200)) {
                    return Mono.just(acc);
                }
                return Mono.just(NcmResponse.message(401, "A valid login cookie is required"));
            }
            var state = new EventState(uid, new ArrayList<>(acc.cookies()));
            return nextEventPage(state, opt);
        });
    }

    static final class EventState {
        final String uid;
        final List<String> cookies;
        final ArrayNode events = JSON.arrayNode();
        final Set<String> ids = new HashSet<>();
        final Set<String> cursors = new HashSet<>();
        Object lasttime = -1L;
        int pageCount;
        Long size;

        EventState(String uid, List<String> cookies) {
            this.uid = uid;
            this.cookies = cookies;
        }
    }

    Mono<NcmResponse> nextEventPage(EventState s, RequestOptions opt) {
        s.pageCount++;
        Map<String, Object> data = map("getcounts", true, "time", s.lasttime, "limit", (long) EVENT_PAGE_SIZE,
            "total", false, "fromRN", "true");
        return client.request("/api/event/get/" + s.uid, data, opt.withCrypto("")).flatMap(page -> {
            s.cookies.addAll(page.cookies());
            JsonNode b = page.bodyObject();
            if (!page.ok() || b.path("code").asInt() != 200) {
                return Mono.just(new NcmResponse(page.status(), page.body(), s.cookies));
            }
            if (s.pageCount == 1 && b.hasNonNull("size")) {
                double n = Js.number(b.get("size").isNumber() ? b.get("size").numberValue() : b.get("size").asText());
                s.size = Double.isNaN(n) || Double.isInfinite(n) ? null : (long) n;
            }
            for (JsonNode ev : b.path("events")) {
                JsonNode id = ev.get("id");
                if (id == null || id.isNull()) {
                    s.events.add(ev);
                } else if (s.ids.add(id.asText())) {
                    s.events.add(ev);
                }
            }
            boolean more = Js.truthy(b.path("more").asBoolean(false));
            JsonNode lt = b.get("lasttime");
            s.lasttime = lt == null || lt.isNull() ? null : (lt.isNumber() ? (Object) lt.asLong() : lt.asText());
            if (more) {
                String cursor = s.lasttime == null ? "" : Js.string(s.lasttime);
                if (cursor.isEmpty() || !s.cursors.add(cursor)) {
                    return Mono.just(eventError("Upstream event pagination cursor stalled", s.cookies));
                }
                if (s.pageCount >= EVENT_MAX_PAGES) {
                    return Mono.just(eventError("Upstream event pagination exceeded " + EVENT_MAX_PAGES + " pages",
                        s.cookies));
                }
                return nextEventPage(s, opt);
            }
            int got = s.events.size();
            ObjectNode out = JSON.objectNode();
            out.put("code", 200);
            out.set("events", s.events);
            if (s.size == null) {
                out.putNull("size");
                out.putNull("unavailableCount");
                out.putNull("sizeMismatch");
            } else {
                out.put("size", s.size);
                out.put("unavailableCount", Math.max(s.size - got, 0));
                out.put("sizeMismatch", s.size != got);
            }
            out.put("retrievedCount", got);
            out.put("pageCount", s.pageCount);
            out.put("more", false);
            if (s.lasttime == null) {
                out.putNull("lasttime");
            } else {
                out.set("lasttime", MAPPER.valueToTree(s.lasttime));
            }
            return Mono.just(new NcmResponse(200, out, s.cookies));
        });
    }

    static NcmResponse eventError(String message, List<String> cookies) {
        ObjectNode b = JSON.objectNode();
        b.put("code", 502);
        b.put("message", message);
        return new NcmResponse(502, b, cookies);
    }

    /** module/ad_get.js */
    public Mono<NcmResponse> adGet(Map<String, String> q, RequestOptions opt) {
        return client.request("/api/ad/get", map("type_ids", or(q.get("type_ids"), "[\"400002_0\"]")),
            opt.withCrypto("xeapi").withCheckToken("v3")).map(res -> {
                if (!res.ok()) {
                    return res;
                }
                JsonNode raw = res.bodyObject();
                JsonNode ads = raw.get("ads");
                String reqId = "";
                JsonNode ad = firstAd(ads);
                if (ad != null) {
                    JsonNode extMap = parseMaybe(ad.get("adExtMap"));
                    reqId = extMap.path("req_id").asText("");
                    if (reqId.isEmpty()) {
                        reqId = ad.path("adLogId").path("requestId").asText("");
                    }
                    if (reqId.isEmpty()) {
                        reqId = ad.path("reqId").asText("");
                    }
                    if (reqId.isEmpty()) {
                        reqId = parseMaybe(ad.get("extJson")).path("contextInfo").path("req_id").asText("");
                    }
                }
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.set("ads", ads == null || ads.isNull() ? JSON.nullNode() : ads);
                JsonNode msg = raw.get("message");
                b.set("message", msg == null || msg.isNull() || msg.asText().isEmpty() ? JSON.nullNode() : msg);
                ObjectNode extra = b.putObject("extra");
                extra.put("reqId", reqId);
                return NcmResponse.ok(b, res.cookies());
            });
    }

    static JsonNode firstAd(JsonNode ads) {
        if (ads == null || !ads.isObject()) {
            return null;
        }
        var it = ads.elements();
        return it.hasNext() ? it.next() : null;
    }

    /** 字符串就当 JSON 解析（失败返回空对象），对象原样返回。 */
    static JsonNode parseMaybe(JsonNode n) {
        if (n == null || n.isNull()) {
            return JSON.objectNode();
        }
        if (n.isTextual()) {
            try {
                JsonNode p = MAPPER.readTree(n.asText());
                return p == null ? JSON.objectNode() : p;
            } catch (Exception e) {
                return JSON.objectNode();
            }
        }
        return n;
    }

    /** module/ad_listening_rights_gain.js：看广告领免费听歌权益。 */
    Mono<NcmResponse> adListeningRightsGain(Map<String, String> q, RequestOptions opt) {
        long time = System.currentTimeMillis();
        boolean needAd = blank(q.get("reqUid")) || q.get("contextInfo") == null || q.get("creativeType") == null;
        Mono<JsonNode> adMono = needAd
            ? adGet(Map.of("type_ids", or(q.get("type_ids"), "[\"400002_0\"]")), opt)
                .map(r -> (JsonNode) r.bodyObject()).onErrorReturn(JSON.objectNode())
            : Mono.just(JSON.objectNode());
        return adMono.flatMap(adRes -> {
            String reqUid = or(q.get("reqUid"), "");
            String contextInfo = q.get("contextInfo");
            Object creativeType = q.get("creativeType");
            String generalRightsInfo = q.get("generalRightsInfo");
            ObjectNode hint = JSON.objectNode();
            JsonNode ad = firstAd(adRes.get("ads"));
            if (needAd && ad != null) {
                JsonNode ext = parseMaybe(ad.get("extJson"));
                JsonNode extMap = parseMaybe(ad.get("adExtMap"));
                if (reqUid.isEmpty()) {
                    for (String c : new String[] {ext.path("contextInfo").path("req_id").asText(""),
                        extMap.path("req_id").asText(""), ad.path("adLogId").path("requestId").asText(""),
                        ad.path("reqId").asText(""), adRes.path("extra").path("reqId").asText("")}) {
                        if (!c.isEmpty()) {
                            reqUid = c;
                            break;
                        }
                    }
                }
                if (contextInfo == null) {
                    JsonNode ci = ext.get("contextInfo");
                    if (ci == null || ci.isNull()) {
                        ci = ad.path("adLogId").get("contextInfo");
                    }
                    if (ci == null || ci.isNull()) {
                        ci = ad.get("showContext");
                    }
                    if (ci != null && !ci.isNull()) {
                        contextInfo = ci.isTextual() ? ci.asText() : ci.toString();
                    }
                }
                if (creativeType == null && ad.has("creativeType")) {
                    creativeType = ad.get("creativeType").asText();
                }
                JsonNode gri = ad.get("generalRightsInfo");
                if (generalRightsInfo == null && gri != null && !gri.isNull()) {
                    generalRightsInfo = gri.isTextual() ? gri.asText() : gri.toString();
                }
                if (parseMaybe(ad.get("listeningRightHintInfo")) instanceof ObjectNode h) {
                    hint.setAll(h);
                }
                if (parseMaybe(gri) instanceof ObjectNode g) {
                    hint.setAll(g);
                }
            }
            Object method = !blank(q.get("rightsGainMethod")) ? pi(q.get("rightsGainMethod"))
                : hint.has("rightsGainMethod") && Js.truthy(hintValue(hint, "rightsGainMethod"))
                ? hintValue(hint, "rightsGainMethod") : (Object) 2L;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("reqUid", reqUid);
            p.put("exposureTime", !blank(q.get("exposureTime")) ? pi(q.get("exposureTime")) : time);
            p.put("userId", !blank(q.get("uid")) ? pi(q.get("uid")) : null);
            p.put("clickTime", !blank(q.get("clickTime")) ? pi(q.get("clickTime")) : time);
            p.put("extraRightsType", fromQueryOrHint(q, hint, "extraRightsType"));
            p.put("playContinuously", !blank(q.get("playContinuously")));
            p.put("source", fromQueryOrHint(q, hint, "source"));
            p.put("creativeType", creativeType != null ? pi(Js.string(creativeType)) : 36L);
            p.put("rightsGainMethod", method);
            p.put("extraRightsGainMethod", fromQueryOrHint(q, hint, "extraRightsGainMethod"));
            p.put("extraRightsGainDuration", fromQueryOrHint(q, hint, "extraRightsGainDuration"));
            p.put("nextRightsGainDuration", fromQueryOrHint(q, hint, "nextRightsGainDuration"));
            p.put("rightsGainType", fromQueryOrHint(q, hint, "rightsGainType"));
            p.put("rightsGainDuration", fromQueryOrHint(q, hint, "rightsGainDuration"));
            p.put("gainMethodStep", !blank(q.get("gainMethodStep")) ? pi(q.get("gainMethodStep")) : null);
            p.put("generalRightsInfo", generalRightsInfo);
            Object rightsExt = !blank(q.get("rightsExtJson")) ? q.get("rightsExtJson")
                : Js.truthy(hintValue(hint, "rightsExtJson")) ? hintValue(hint, "rightsExtJson") : null;
            p.put("rightsExtJson", rightsExt);
            try {
                p.put("appInfo", !blank(q.get("appInfo")) ? MAPPER.readValue(q.get("appInfo"), Object.class) : null);
            } catch (Exception e) {
                return Mono.just(NcmResponse.error(400, "appInfo 不是合法 JSON"));
            }
            p.put("contextInfo", contextInfo);
            p.put("installed", !blank(q.get("installed")) ? pi(q.get("installed")) : null);
            boolean sniff = method instanceof Number n && (n.longValue() == 3 || n.longValue() == 6);
            p.put("sniffTime", sniff ? (!blank(q.get("sniffTime")) ? pi(q.get("sniffTime")) : time) : null);
            p.values().removeIf(v -> v == null);
            return client.request("/api/ad/listening/rights/gain", map("reqParam", Js.json(p)),
                opt.withCrypto("xeapi").withCheckToken("v3")).map(res -> {
                    if (!res.ok()) {
                        return res;
                    }
                    ObjectNode b = JSON.objectNode();
                    b.put("code", 200);
                    b.set("data", res.body());
                    return NcmResponse.ok(b, res.cookies());
                });
        });
    }

    static Object hintValue(ObjectNode hint, String key) {
        JsonNode v = hint.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isNumber() ? (Object) Js.numeric(v.asDouble()) : v.isBoolean() ? (Object) v.asBoolean() : v.asText();
    }

    static Object fromQueryOrHint(Map<String, String> q, ObjectNode hint, String key) {
        if (!blank(q.get(key))) {
            return pi(q.get(key));
        }
        Object h = hintValue(hint, key);
        return h != null ? pi(Js.string(h)) : null;
    }

    /** parseInt，NaN 时 JSON 序列化成 null。 */
    static Object pi(String s) {
        return cn.sky.ncm.api.ExprEval.eval("parseInt(query.v)", new java.util.HashMap<>(Map.of("v", s)));
    }

    /** module/playlist_tracks.js：op=add/del；遇到 512 时把 trackIds 重复一遍再试（上游的绕过方式）。 */
    Mono<NcmResponse> playlistTracks(Map<String, String> q, RequestOptions opt) {
        if (q.get("tracks") == null) {
            return Mono.just(NcmResponse.error(400, "缺少参数: tracks"));
        }
        List<String> tracks = Arrays.asList(q.get("tracks").split(",", -1));
        RequestOptions o = opt.withCrypto("");
        return client.request("/api/playlist/manipulate/tracks", tracksData(q, tracks), o).flatMap(res -> {
            if (res.ok()) {
                ObjectNode b = JSON.objectNode();
                b.put("status", res.status());
                b.set("body", res.body());
                ArrayNode c = b.putArray("cookie");
                res.cookies().forEach(c::add);
                return Mono.just(NcmResponse.ok(b, res.cookies()));
            }
            if (res.bodyObject().path("code").asInt() == 512) {
                List<String> doubled = new ArrayList<>(tracks);
                doubled.addAll(tracks);
                return client.request("/api/playlist/manipulate/tracks", tracksData(q, doubled), o);
            }
            return Mono.just(NcmResponse.ok(res.body(), res.cookies()));
        });
    }

    static Map<String, Object> tracksData(Map<String, String> q, List<String> tracks) {
        return map("op", q.get("op"), "pid", q.get("pid"), "trackIds", Js.json(tracks), "imme", "true");
    }

    /** module/scrobble.js：听歌打卡（旧版，走 clientlog 域名的 weblog）。 */
    Mono<NcmResponse> scrobble(Map<String, String> q, RequestOptions opt) {
        Map<String, String> cookie = new LinkedHashMap<>(opt.cookieOrEmpty());
        cookie.put("os", "osx");
        RequestOptions o = opt.withCookie(cookie).withCrypto("eapi").withDomain(CLIENTLOG_DOMAIN);
        String content = "id=" + Js.string(q.get("sourceid"));
        String start = Js.json(List.of(map("action", "startplay", "json", map(
            "id", q.get("id"), "type", "song", "mainsite", "1", "mainsiteWeb", "1", "content", content))));
        String play = Js.json(List.of(map("action", "play", "json", map(
            "download", 0L, "end", "playend", "id", q.get("id"), "sourceId", q.get("sourceid"), "time", q.get("time"),
            "type", "song", "wifi", 0L, "source", "list", "mainsite", "1", "mainsiteWeb", "1", "content", content))));
        return client.request("/api/feedback/weblog", map("logs", start), o).flatMap(r1 -> {
            if (!r1.ok()) {
                return Mono.just(r1);
            }
            return client.request("/api/feedback/weblog", map("logs", play), o).map(r2 -> {
                if (!r2.ok()) {
                    return r2;
                }
                ObjectNode b = JSON.objectNode();
                b.put("code", 200);
                b.put("data", "success");
                ObjectNode d = b.putObject("details");
                d.set("startplay", r1.body());
                d.set("play", r2.body());
                return NcmResponse.ok(b, List.of());
            });
        });
    }
}

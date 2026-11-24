package cn.sky.ncm.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import cn.sky.ncm.crypto.NcmCrypto;
import cn.sky.ncm.crypto.XeapiCrypto;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 对应上游 util/request.js（weapi / linuxapi / eapi / api / xeapi 五种分支）。 */
@Component
public class NcmClient {

    public static final String DOMAIN = "https://music.163.com";
    public static final String API_DOMAIN = "https://interface.music.163.com";
    public static final String EAPI_DOMAIN = "https://interfacepc.music.163.com";
    public static final String XEAPI_DOMAIN = "https://interface3.music.163.com";

    static final String UA_WEAPI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";
    static final String UA_LINUX = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36";
    static final String UA_IPHONE = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    static final String UA_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    public static final String UA_ANDROID = "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)";

    record OsInfo(String os, String appver, String osver, String channel) {
    }

    static final Map<String, OsInfo> OS_MAP = Map.of(
        "pc", new OsInfo("pc", "3.1.17.204416", "Microsoft-Windows-10-Professional-build-19045-64bit", "netease"),
        "linux", new OsInfo("linux", "1.2.1.0428", "Deepin 20.9", "netease"),
        "android", new OsInfo("android", "8.20.20.231215173437", "14", "xiaomi"),
        "iphone", new OsInfo("iPhone OS", "9.0.90", "16.2", "distribution"),
        "osx", new OsInfo("osx", "3.1.10.5100", "15.5", "netease"));

    static final Set<Integer> SPECIAL_STATUS_CODES = Set.of(201, 302, 400, 502, 800, 801, 802, 803);
    static final Pattern NMTID_PATTERN = Pattern.compile("(?:^|;\\s*)NMTID=([^;]+)");
    static final Pattern DOMAIN_ATTR = Pattern.compile("\\s*Domain=[^;]+;*", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final NcmHttp http;
    private final SettingService settings;
    private final DeviceState device;
    private final CheckTokenService checkTokens;

    public NcmClient(DeviceState device, CheckTokenService checkTokens, NcmHttp http, SettingService settings) {
        this.device = device;
        this.checkTokens = checkTokens;
        this.http = http;
        this.settings = settings;
    }

    public DeviceState device() {
        return device;
    }

    public Mono<NcmResponse> request(String uri, Map<String, Object> data, RequestOptions options) {
        // 先读一次网络设置，保证代理是最新的（设置有 10 秒缓存）
        return settings.advanced()
            .then(Mono.defer(() -> checkTokens.token(options.checkToken())))
            .flatMap(token -> doRequest(uri, data, options, token));
    }

    private Mono<NcmResponse> doRequest(String uri, Map<String, Object> data, RequestOptions options, String token) {
        String crypto = options.crypto();
        if (crypto == null || crypto.isEmpty() || "default".equals(crypto)) {
            crypto = "eapi";
        }
        final String cryptoName = crypto;
        final boolean withToken = notEmpty(options.checkToken());
        Map<String, Object> body = new LinkedHashMap<>(data);
        Map<String, String> cookie = processCookie(options.cookie() == null ? Map.of() : options.cookie(), cryptoName);
        Map<String, String> headers = new LinkedHashMap<>();
        if (options.headers() != null) {
            options.headers().forEach((k, v) -> {
                if (!"cookie".equalsIgnoreCase(k)) {
                    headers.put(k, v);
                }
            });
        }
        final String domain = notEmpty(options.domain()) ? options.domain() : null;

        String csrf = cookie.getOrDefault("__csrf", "");
        body.put("e_r", false);

        final String url;
        final Map<String, String> form;
        switch (cryptoName) {
            case "weapi" -> {
                headers.put("Referer", or(domain, DOMAIN));
                headers.put("User-Agent", or(options.ua(), UA_WEAPI));
                headers.put("Cookie", cookieObjToString(cookie));
                if (withToken) {
                    headers.put("X-antiCheatToken", token);
                }
                body.put("csrf_token", csrf);
                form = NcmCrypto.weapi(Js.json(body));
                url = or(domain, DOMAIN) + "/weapi/" + uri.substring(5);
            }
            case "linuxapi" -> {
                headers.put("User-Agent", or(options.ua(), UA_LINUX));
                headers.put("Cookie", cookieObjToString(cookie));
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("method", "POST");
                wrap.put("url", or(domain, DOMAIN) + uri);
                wrap.put("params", body);
                form = NcmCrypto.linuxapi(Js.json(wrap));
                url = or(domain, DOMAIN) + "/api/linux/forward";
            }
            case "xeapi" -> {
                var key = device.xeapiPublicKey();
                if (key == null) {
                    return Mono.just(NcmResponse.error(503, "xeapi 公钥尚未获取，请稍后重试或在后台刷新设备状态"));
                }
                String os = "android";
                String appver = "android".equals(cookie.get("os")) && notEmpty(cookie.get("appver"))
                    ? cookie.get("appver") : "9.1.65";
                String osver = "android".equals(cookie.get("os")) && notEmpty(cookie.get("osver"))
                    ? cookie.get("osver") : "16";
                String buildver = or(cookie.get("buildver"), String.valueOf(System.currentTimeMillis()).substring(0, 10));
                String sDeviceId = or(cookie.get("sDeviceId"), cookie.get("deviceId"));
                headers.put("User-Agent", or(options.ua(), UA_ANDROID));
                headers.put("X-Client-Enc-State", "ENCRYPTED");
                headers.put("x-aeapi", "true");
                headers.put("x-deviceid", cookie.get("deviceId"));
                headers.put("x-os", os);
                headers.put("x-osver", osver);
                headers.put("x-appver", appver);
                headers.put("x-sdeviceid", sDeviceId);
                headers.put("x-buildver", buildver);
                if (notEmpty(cookie.get("MUSIC_U"))) {
                    headers.put("x-music-u", cookie.get("MUSIC_U"));
                }
                if (withToken) {
                    headers.put("X-antiCheatToken", token);
                }
                Map<String, String> xc = new LinkedHashMap<>(cookie);
                xc.put("os", os);
                xc.put("osver", osver);
                xc.put("appver", appver);
                xc.put("buildver", buildver);
                xc.put("sDeviceId", sDeviceId);
                headers.put("Cookie", cookieObjToString(xc));
                url = or(domain, XEAPI_DOMAIN) + "/xeapi/" + uri.substring(5);
                form = XeapiCrypto.encrypt(uri, body,
                    new XeapiCrypto.PublicKeyState(key.publicKey(), key.version(), key.sk()),
                    device.xeapiSessionId(), device.xeapiSessionKey(), os);
            }
            case "eapi", "api" -> {
                Map<String, String> header = new LinkedHashMap<>();
                header.put("osver", cookie.get("osver"));
                header.put("deviceId", cookie.get("deviceId"));
                header.put("os", cookie.get("os"));
                header.put("appver", cookie.get("appver"));
                header.put("versioncode", or(cookie.get("versioncode"), "140"));
                header.put("mobilename", or(cookie.get("mobilename"), ""));
                header.put("buildver", or(cookie.get("buildver"), String.valueOf(System.currentTimeMillis()).substring(0, 10)));
                header.put("resolution", or(cookie.get("resolution"), "1920x1080"));
                header.put("__csrf", csrf);
                header.put("channel", cookie.get("channel"));
                header.put("requestId", System.currentTimeMillis() + "_" + String.format("%04d", RANDOM.nextInt(1000)));
                if (notEmpty(cookie.get("MUSIC_U"))) {
                    header.put("MUSIC_U", cookie.get("MUSIC_U"));
                }
                if (notEmpty(cookie.get("MUSIC_A"))) {
                    header.put("MUSIC_A", cookie.get("MUSIC_A"));
                }
                if (withToken) {
                    header.put("X-antiCheatToken", token);
                }
                if ("eapi".equals(cryptoName) && notEmpty(cookie.get("NMTID"))) {
                    header.put("NMTID", cookie.get("NMTID"));
                }
                headers.put("Cookie", cookieObjToString(header));
                headers.put("User-Agent", or(options.ua(), "osx".equals(cookie.get("os")) ? UA_MAC : UA_IPHONE));
                if ("eapi".equals(cryptoName)) {
                    body.put("header", header);
                    form = NcmCrypto.eapi(uri, Js.json(body));
                    url = or(domain, EAPI_DOMAIN) + "/eapi/" + uri.substring(5);
                } else {
                    Map<String, String> plain = new LinkedHashMap<>();
                    body.forEach((k, v) -> plain.put(k, Js.string(v)));
                    form = plain;
                    url = or(domain, API_DOMAIN) + uri;
                }
            }
            default -> {
                return Mono.error(new UnsupportedCryptoException(cryptoName));
            }
        }

        boolean probeNmtid = "eapi".equals(cryptoName) && device.nmtid() == null
            && device.nmtidRetries().get() > 0 && !notEmpty(options.cookie() == null ? null : options.cookie().get("NMTID"));
        boolean xeapi = "xeapi".equals(cryptoName);

        return http.current().post()
            .uri(URI.create(url))
            .headers(h -> {
                headers.forEach((k, v) -> {
                    if (v != null) {
                        h.set(k, v);
                    }
                });
                h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            })
            .bodyValue(formEncode(form))
            .exchangeToMono(resp -> resp.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .map(bytes -> toResponse(resp.statusCode().value(), resp.headers().asHttpHeaders(), bytes,
                    probeNmtid, xeapi)))
            .timeout(Duration.ofSeconds(15))
            .onErrorResume(e -> Mono.just(NcmResponse.error(502, String.valueOf(e.getMessage()))));
    }

    private NcmResponse toResponse(int httpStatus, HttpHeaders respHeaders, byte[] bytes, boolean probeNmtid,
                                   boolean xeapi) {
        List<String> setCookies = respHeaders.getOrEmpty(HttpHeaders.SET_COOKIE);
        if (probeNmtid) {
            device.nmtidRetries().decrementAndGet();
            for (String c : setCookies) {
                Matcher m = NMTID_PATTERN.matcher(c);
                if (m.find()) {
                    device.nmtid(m.group(1));
                }
            }
        }
        if (xeapi) {
            String ssid = respHeaders.getFirst("x-encr-ssid");
            String sskey = respHeaders.getFirst("x-encr-sskey");
            if (notEmpty(ssid) && notEmpty(sskey)) {
                device.xeapiSession(ssid, sskey);
            }
        }
        List<String> cookies = setCookies.stream().map(c -> DOMAIN_ATTR.matcher(c).replaceFirst("")).toList();
        JsonNode node;
        int status;
        try {
            node = parseBody(bytes, xeapi);
            int code = node.path("code").asInt(0);
            if (node instanceof ObjectNode on && node.has("code")) {
                on.put("code", code);
            }
            status = code != 0 ? code : httpStatus;
            if (SPECIAL_STATUS_CODES.contains(code)) {
                status = 200;
            }
        } catch (Exception e) {
            node = TextNode.valueOf(new String(bytes, UTF_8));
            status = httpStatus;
        }
        if (status <= 100 || status >= 600) {
            status = 400;
        }
        return new NcmResponse(status, node, cookies);
    }

    static JsonNode parseBody(byte[] bytes, boolean xeapi) throws Exception {
        if (xeapi) {
            try {
                return MAPPER.readTree(XeapiCrypto.decryptResponse(bytes));
            } catch (Exception decryptFailed) {
                // 出错时服务端可能直接返回明文 JSON
            }
        }
        JsonNode node = MAPPER.readTree(bytes);
        if (node == null || node.isMissingNode()) {
            throw new IllegalStateException("empty body");
        }
        return node;
    }

    /**
     * Cookie 里的 os 对应的客户端信息；为空或不认识时用 pc。
     * 注意 OS_MAP 是 Map.of 创建的不可变映射，用 null 查询会抛 NullPointerException，所以先判空。
     */
    static OsInfo osInfo(String os) {
        OsInfo info = os == null ? null : OS_MAP.get(os);
        return info != null ? info : OS_MAP.get("pc");
    }

    /** 对应上游 processCookieObject。 */
    Map<String, String> processCookie(Map<String, String> cookie, String crypto) {
        Map<String, String> p = new LinkedHashMap<>(cookie);
        String nuid = randomHex(32);
        OsInfo os = osInfo(cookie.get("os"));
        p.put("__remember_me", "true");
        p.put("ntes_kaola_ad", "1");
        p.put("_ntes_nuid", or(cookie.get("_ntes_nuid"), nuid));
        p.put("_ntes_nnid", or(cookie.get("_ntes_nnid"), nuid + "," + System.currentTimeMillis()));
        p.put("WNMCID", or(cookie.get("WNMCID"), device.wnmcid()));
        p.put("WEVNSM", or(cookie.get("WEVNSM"), "1.0.0"));
        p.put("osver", or(cookie.get("osver"), os.osver()));
        p.put("deviceId", or(cookie.get("deviceId"), device.deviceId()));
        p.put("os", or(cookie.get("os"), os.os()));
        p.put("channel", or(cookie.get("channel"), os.channel()));
        p.put("appver", or(cookie.get("appver"), os.appver()));
        if (notEmpty(cookie.get("NMTID"))) {
            p.put("NMTID", cookie.get("NMTID"));
        } else if (device.nmtid() != null) {
            p.put("NMTID", device.nmtid());
        } else if (device.nmtidRetries().get() <= 0 || !"eapi".equals(crypto)) {
            p.put("NMTID", "00O" + randomHex(19));
        }
        if (!notEmpty(p.get("MUSIC_U"))) {
            String a = or(p.get("MUSIC_A"), device.anonymousToken());
            if (a != null) {
                p.put("MUSIC_A", a);
            }
        }
        return p;
    }

    public static String toJson(Object value) {
        return Js.json(value);
    }

    static String cookieObjToString(Map<String, String> cookie) {
        var joiner = new StringJoiner("; ");
        cookie.forEach((k, v) -> joiner.add(encodeURIComponent(k) + "=" + encodeURIComponent(Js.string(v))));
        return joiner.toString();
    }

    /** 对应 URLSearchParams.toString()。 */
    static String formEncode(Map<String, String> form) {
        var joiner = new StringJoiner("&");
        form.forEach((k, v) -> joiner.add(URLEncoder.encode(k, UTF_8) + "=" + URLEncoder.encode(String.valueOf(v), UTF_8)));
        return joiner.toString();
    }

    /** 对应 JS 的 encodeURIComponent。 */
    public static String encodeURIComponent(String s) {
        var sb = new StringBuilder();
        for (byte b : s.getBytes(UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || "-_.!~*'()".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HEX.formatHex(b);
    }



    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    static String or(String v, String fallback) {
        return notEmpty(v) ? v : fallback;
    }
}

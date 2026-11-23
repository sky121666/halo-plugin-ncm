package cn.sky.ncm.client;

import java.util.Map;

/**
 * 对应上游 createOption 的结果。
 *
 * @param crypto     weapi / eapi / linuxapi / api / xeapi；空或 default 时走 eapi（上游 APP_CONF.encrypt=true）
 * @param cookie     本次请求使用的网易云 Cookie（站长、访客或游客）
 * @param checkToken 易盾反作弊 token：""、"v2"、"v3"（v2 暂用 v3 代替）
 * @param domain     覆盖默认域名（上游 options.domain，例如听歌打卡用 clientlog.music.163.com）
 * @param headers    额外请求头（上游 options.headers）
 */
public record RequestOptions(String crypto, Map<String, String> cookie, String ua, String realIp,
                             boolean randomCnIp, String checkToken, String domain, Map<String, String> headers) {

    public RequestOptions(String crypto, Map<String, String> cookie, String ua, String realIp, boolean randomCnIp) {
        this(crypto, cookie, ua, realIp, randomCnIp, "", "", Map.of());
    }

    public RequestOptions(String crypto, Map<String, String> cookie, String ua, String realIp, boolean randomCnIp,
                          String checkToken) {
        this(crypto, cookie, ua, realIp, randomCnIp, checkToken, "", Map.of());
    }

    public RequestOptions withCrypto(String v) {
        return new RequestOptions(v, cookie, ua, realIp, randomCnIp, checkToken, domain, headers);
    }

    public RequestOptions withCheckToken(String v) {
        return new RequestOptions(crypto, cookie, ua, realIp, randomCnIp, v == null ? "" : v, domain, headers);
    }

    public RequestOptions withCookie(Map<String, String> v) {
        return new RequestOptions(crypto, v, ua, realIp, randomCnIp, checkToken, domain, headers);
    }

    public RequestOptions withDomain(String v) {
        return new RequestOptions(crypto, cookie, ua, realIp, randomCnIp, checkToken, v == null ? "" : v, headers);
    }

    public RequestOptions withHeaders(Map<String, String> v) {
        return new RequestOptions(crypto, cookie, ua, realIp, randomCnIp, checkToken, domain, v == null ? Map.of() : v);
    }

    public Map<String, String> cookieOrEmpty() {
        return cookie == null ? Map.of() : cookie;
    }
}

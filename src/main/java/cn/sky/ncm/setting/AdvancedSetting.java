package cn.sky.ncm.setting;

import java.util.Arrays;
import java.util.List;

/**
 * 插件设置「网络与限流」分组。
 *
 * @param proxy             出站代理，空为直连
 * @param trustProxyHeaders 是否用 X-Forwarded-For / X-Real-IP 识别访客 IP；只有请求来自可信代理时才生效，见 ClientIp
 * @param rateLimitPublic   公开接口每个 IP 每分钟最多请求数，0 为不限制
 * @param rateLimitUser     个人 / 写操作接口每个用户每分钟最多请求数，0 为不限制
 * @param maxUploadMb       上传文件大小上限（MB）
 * @param trustedProxies    可信代理地址：IP 或网段，英文逗号或换行分隔；为空时为本机和内网地址
 * @param proxyHops         代理层数（1~5）：X-Forwarded-For 从右往左数第几个是访客 IP。例如 CDN + Nginx 为 2
 */
public record AdvancedSetting(String proxy, Boolean trustProxyHeaders, Integer rateLimitPublic, Integer rateLimitUser,
                              Integer maxUploadMb, String trustedProxies, Integer proxyHops, String proxySecret,
                              Integer musicSearchPerMinute, Integer musicSuggestPerMinute, Integer siteContentCacheSeconds) {

    public static final AdvancedSetting DEFAULT = new AdvancedSetting("", true, 120, 120, 100, "", 1, "", null, null, null);

    public boolean trustHeaders() {
        return trustProxyHeaders == null || trustProxyHeaders;
    }

    public List<String> trustedProxyList() {
        if (trustedProxies == null || trustedProxies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trustedProxies.split("[,，\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .limit(50)
            .toList();
    }

    public int proxyHopCount() {
        return proxyHops == null ? 1 : Math.max(1, Math.min(proxyHops, 5));
    }

    public int publicLimit() {
        return rateLimitPublic == null ? 120 : rateLimitPublic;
    }

    public int userLimit() {
        return rateLimitUser == null ? 120 : rateLimitUser;
    }

    public int maxUploadBytes() {
        int mb = maxUploadMb == null || maxUploadMb <= 0 ? 100 : Math.min(maxUploadMb, 1024);
        return mb * 1024 * 1024;
    }
}

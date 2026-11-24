package cn.sky.ncm.security;

import cn.sky.ncm.setting.AdvancedSetting;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * 识别访客 IP（用于限流和统计）。
 * <p>
 * 只有直接连到 Halo 的地址是可信代理时，才看 X-Forwarded-For / X-Real-IP，否则任何人都能在请求头里填一个假 IP 绕过限流：
 * <ul>
 *   <li>可信代理：设置「高级 → 可信代理地址」里的 IP / 网段；没填时只信任本机环回地址</li>
 *   <li>X-Forwarded-For：从<b>右往左</b>数第 N 个（N 为「代理层数」，默认 1）。左边的值是客户端自己能填的，右边的是代理追加的</li>
 *   <li>没有 X-Forwarded-For 时用 X-Real-IP</li>
 *   <li>请求头里的值必须是 IP 字面量，否则忽略（不做 DNS 解析）</li>
 * </ul>
 */
public final class ClientIp {

    static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private ClientIp() {
    }

    public static String resolve(ServerRequest request, AdvancedSetting adv) {
        String remote = request.remoteAddress()
            .map(a -> a.getAddress() == null ? a.getHostString() : a.getAddress().getHostAddress())
            .orElse(null);
        return resolve(remote, request.headers().header("X-Forwarded-For"),
            request.headers().firstHeader("X-Real-IP"), adv);
    }

    /**
     * @param remote 直接连到 Halo 的地址
     * @param xff    所有 X-Forwarded-For 请求头（可能有多个，每个里面用逗号分隔）
     */
    public static String resolve(String remote, List<String> xff, String xRealIp, AdvancedSetting adv) {
        String peer = remote == null || remote.isBlank() ? "unknown" : strip(remote);
        if (!adv.trustHeaders() || !trustedProxy(peer, adv.trustedProxyList())) {
            return peer;
        }
        List<String> chain = new ArrayList<>();
        if (xff != null) {
            for (String h : xff) {
                if (h == null) {
                    continue;
                }
                for (String part : h.split(",")) {
                    String p = strip(part.trim());
                    if (!p.isEmpty()) {
                        chain.add(p);
                    }
                }
            }
        }
        if (!chain.isEmpty()) {
            String ip = chain.get(Math.max(0, chain.size() - adv.proxyHopCount()));
            return literal(ip) ? ip : peer;
        }
        String real = xRealIp == null ? "" : strip(xRealIp.trim());
        return literal(real) ? real : peer;
    }

    /** 去掉 IPv6 的方括号、端口和 zone id。 */
    static String strip(String s) {
        String v = s.trim();
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            v = end > 0 ? v.substring(1, end) : v.substring(1);
        } else if (v.indexOf(':') > 0 && v.indexOf(':') == v.lastIndexOf(':') && v.contains(".")) {
            // 1.2.3.4:5678
            v = v.substring(0, v.indexOf(':'));
        }
        int zone = v.indexOf('%');
        return zone > 0 ? v.substring(0, zone) : v;
    }

    static boolean literal(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (IPV4.matcher(s).matches()) {
            return true;
        }
        return s.indexOf(':') >= 0 && IPV6.matcher(s).matches() && parse(s) != null;
    }

    static InetAddress parse(String s) {
        if (!(IPV4.matcher(s).matches() || (s.indexOf(':') >= 0 && IPV6.matcher(s).matches()))) {
            return null;
        }
        try {
            // 只会是 IP 字面量，不会触发 DNS 查询
            return InetAddress.getByName(s);
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    /** 直接连接的地址是否可信代理。configured 为空时只信任环回地址。 */
    static boolean trustedProxy(String peer, List<String> configured) {
        InetAddress a = parse(peer);
        if (a == null) {
            return false;
        }
        if (configured.isEmpty()) {
            return a.isLoopbackAddress();
        }
        for (String c : configured) {
            if (inCidr(a, c)) {
                return true;
            }
        }
        return false;
    }

    /** c 为单个 IP 或 CIDR（例如 10.0.0.0/8、2001:db8::/32）。格式不对时不匹配。 */
    static boolean inCidr(InetAddress a, String c) {
        String[] parts = c.trim().split("/", 2);
        InetAddress net = parse(parts[0].trim());
        if (net == null) {
            return false;
        }
        byte[] x = a.getAddress();
        byte[] y = net.getAddress();
        if (x.length != y.length) {
            return false;
        }
        int bits = x.length * 8;
        if (parts.length == 2) {
            try {
                bits = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (bits < 0 || bits > x.length * 8) {
                return false;
            }
        }
        int full = bits / 8;
        for (int i = 0; i < full; i++) {
            if (x[i] != y[i]) {
                return false;
            }
        }
        int rest = bits % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xff << (8 - rest);
        return (x[full] & mask) == (y[full] & mask);
    }
}

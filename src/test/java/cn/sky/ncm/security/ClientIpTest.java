package cn.sky.ncm.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.setting.AdvancedSetting;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpTest {

    static AdvancedSetting adv(Boolean trust, String proxies, Integer hops) {
        return new AdvancedSetting("", trust, 120, 120, 100, proxies, hops, "", null, null, null, null, null, null);
    }

    static final AdvancedSetting DEFAULT = AdvancedSetting.DEFAULT;

    @Test
    void directPublicClientCannotSpoof() {
        // Halo 直接暴露在公网：请求头一律不信
        assertEquals("203.0.113.9", ClientIp.resolve("203.0.113.9", List.of("1.1.1.1"), "2.2.2.2", DEFAULT));
    }

    @Test
    void behindLocalNginxTakesRightmost() {
        // Nginx 用 $proxy_add_x_forwarded_for：客户端伪造的值在左边，真实地址被追加在右边
        assertEquals("198.51.100.7",
            ClientIp.resolve("127.0.0.1", List.of("6.6.6.6, 198.51.100.7"), null, DEFAULT));
        assertEquals("172.18.0.2",
            ClientIp.resolve("172.18.0.2", List.of("6.6.6.6", "198.51.100.7"), null, DEFAULT));
    }

    @Test
    void proxyHops() {
        var two = adv(true, "10.0.0.5", 2);
        assertEquals("198.51.100.7",
            ClientIp.resolve("10.0.0.5", List.of("6.6.6.6, 198.51.100.7, 104.16.0.1"), null, two));
        // 链比层数短时取最左边
        assertEquals("198.51.100.7", ClientIp.resolve("10.0.0.5", List.of("198.51.100.7"), null, two));
    }

    @Test
    void realIpWhenNoXff() {
        assertEquals("198.51.100.7", ClientIp.resolve("::1", null, " 198.51.100.7 ", DEFAULT));
        assertEquals("127.0.0.1", ClientIp.resolve("127.0.0.1", null, "evil.example.com", DEFAULT));
    }

    @Test
    void invalidHeaderValuesFallBackToPeer() {
        assertEquals("127.0.0.1", ClientIp.resolve("127.0.0.1", List.of("unknown"), null, DEFAULT));
        assertEquals("127.0.0.1", ClientIp.resolve("127.0.0.1", List.of("<script>"), null, DEFAULT));
        assertEquals("2001:db8::1", ClientIp.resolve("127.0.0.1", List.of("[2001:db8::1]:443"), null, DEFAULT));
        assertEquals("198.51.100.7", ClientIp.resolve("127.0.0.1", List.of("198.51.100.7:5555"), null, DEFAULT));
    }

    @Test
    void switchOffAndConfiguredProxies() {
        assertEquals("127.0.0.1", ClientIp.resolve("127.0.0.1", List.of("198.51.100.7"), null, adv(false, "", 1)));
        var cdnOnly = adv(true, "104.16.0.0/13", 1);
        assertEquals("198.51.100.7", ClientIp.resolve("104.16.3.4", List.of("198.51.100.7"), null, cdnOnly));
        // 配置了可信代理后，内网地址不再默认可信
        assertEquals("10.0.0.5", ClientIp.resolve("10.0.0.5", List.of("198.51.100.7"), null, cdnOnly));
    }

    @Test
    void cidr() throws Exception {
        assertFalse(ClientIp.trustedProxy("fd00::1", List.of()));
        assertFalse(ClientIp.trustedProxy("192.168.1.1", List.of()));
        assertFalse(ClientIp.trustedProxy("8.8.8.8", List.of()));
        assertTrue(ClientIp.inCidr(ClientIp.parse("10.1.2.3"), "10.0.0.0/8"));
        assertFalse(ClientIp.inCidr(ClientIp.parse("11.1.2.3"), "10.0.0.0/8"));
        assertTrue(ClientIp.inCidr(ClientIp.parse("172.31.255.1"), "172.16.0.0/12"));
        assertTrue(ClientIp.inCidr(ClientIp.parse("2001:db8::5"), "2001:db8::/32"));
        assertTrue(ClientIp.inCidr(ClientIp.parse("1.2.3.4"), "1.2.3.4"));
        assertFalse(ClientIp.inCidr(ClientIp.parse("1.2.3.4"), "1.2.3.4/abc"));
        assertFalse(ClientIp.inCidr(ClientIp.parse("1.2.3.4"), "example.com"));
    }
}

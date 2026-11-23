package cn.sky.ncm.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import cn.sky.ncm.setting.SettingService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 回归测试：游客 Cookie 没有 os 时，processCookie 曾经用 null 查询 Map.of 创建的映射而抛空指针。 */
class NcmClientCookieTest {

    final DeviceState device = new DeviceState();
    final NcmClient client = new NcmClient(device, mock(CheckTokenService.class), mock(NcmHttp.class),
        mock(SettingService.class));

    @Test
    void osInfoHandlesNullAndUnknown() {
        assertEquals("pc", NcmClient.osInfo(null).os());
        assertEquals("pc", NcmClient.osInfo("").os());
        assertEquals("pc", NcmClient.osInfo("windows-phone").os());
        assertEquals("android", NcmClient.osInfo("android").os());
        assertEquals("iPhone OS", NcmClient.osInfo("iphone").os());
    }

    @Test
    void emptyImmutableCookieGetsPcDefaults() {
        Map<String, String> p = assertDoesNotThrow(() -> client.processCookie(Map.of(), "eapi"));
        assertEquals("pc", p.get("os"));
        assertEquals(NcmClient.osInfo("pc").appver(), p.get("appver"));
        assertNotNull(p.get("deviceId"));
        assertNotNull(p.get("_ntes_nuid"));
    }

    @Test
    void cookieWithNullOsValue() {
        Map<String, String> cookie = new HashMap<>();
        cookie.put("os", null);
        cookie.put("MUSIC_U", "x");
        Map<String, String> p = assertDoesNotThrow(() -> client.processCookie(cookie, "weapi"));
        assertEquals("pc", p.get("os"));
        assertEquals("x", p.get("MUSIC_U"));
    }

    @Test
    void keepsKnownOs() {
        Map<String, String> p = client.processCookie(Map.of("os", "android", "appver", "9.0.0"), "eapi");
        assertEquals("android", p.get("os"));
        assertEquals("9.0.0", p.get("appver"));
        assertEquals(NcmClient.osInfo("android").osver(), p.get("osver"));
    }
}

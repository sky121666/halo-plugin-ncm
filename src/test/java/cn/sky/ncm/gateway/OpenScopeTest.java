package cn.sky.ncm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.OpenApis;
import cn.sky.ncm.setting.BasicSetting;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenScopeTest {

    static BasicSetting basic(String scope, String extra, String cap) {
        return new BasicSetting("site", "none", false, 300, "", false, scope, extra, cap);
    }

    @Test
    void defaultOpensOnlyCommonReadApis() {
        BasicSetting d = BasicSetting.DEFAULT;
        assertTrue(d.apiOpen("cloudsearch"));
        assertTrue(d.apiOpen("song_url_v1"));
        assertFalse(d.apiOpen("comment"));
        assertFalse(d.apiOpen("listentogether_room_create"));
        assertTrue(basic("all", "", "exhigh").apiOpen("comment"));
        BasicSetting extra = basic("common", "comment, like\n<bad>", "exhigh");
        assertEquals(Set.of("comment", "like"), extra.extraApiSet());
        assertTrue(extra.apiOpen("like"));
    }

    @Test
    void commonListContainsOnlyExistingReadApis() {
        var registry = new ApiRegistry();
        for (String name : OpenApis.COMMON) {
            var def = registry.find(name).orElseThrow(() -> new AssertionError("不存在的接口：" + name));
            assertTrue("public".equals(def.tier()) || "personal".equals(def.tier()), name + " 不是只读等级");
            assertTrue(def.executable(), name + " 不可用");
        }
    }

    @Test
    void siteAccountIsCapped() {
        Identity site = new Identity("site", Map.of("MUSIC_U", "x"));
        Map<String, String> q = new HashMap<>(Map.of("id", "1", "level", "jymaster"));
        assertNull(NcmGateway.siteAccountLimit("song_url_v1", q, site, basic("common", "", "exhigh")));
        assertEquals("exhigh", q.get("level"));
        q.put("level", "higher");
        NcmGateway.siteAccountLimit("song_url_v1_302", q, site, basic("common", "", "exhigh"));
        assertEquals("higher", q.get("level"));
        NcmGateway.siteAccountLimit("song_url_v1", q, site, basic("common", "", "standard"));
        assertEquals("standard", q.get("level"));

        Map<String, String> br = new HashMap<>(Map.of("id", "1", "br", "999000"));
        NcmGateway.siteAccountLimit("song_url", br, site, basic("common", "", "exhigh"));
        assertEquals("320000", br.get("br"));

        assertEquals(403, NcmGateway.siteAccountLimit("song_download_url_v1", new HashMap<>(), site,
            BasicSetting.DEFAULT).status());
    }

    @Test
    void ownAccountsAreNotCapped() {
        Map<String, String> q = new HashMap<>(Map.of("id", "1", "level", "hires"));
        assertNull(NcmGateway.siteAccountLimit("song_url_v1", q, new Identity("user", Map.of()), BasicSetting.DEFAULT));
        assertNull(NcmGateway.siteAccountLimit("song_download_url_v1", q, Identity.anonymous(), BasicSetting.DEFAULT));
        assertEquals("hires", q.get("level"));
        assertEquals("standard", NcmGateway.capLevel(null, "exhigh"));
        assertEquals("exhigh", NcmGateway.capLevel("lossless", "exhigh"));
    }
}

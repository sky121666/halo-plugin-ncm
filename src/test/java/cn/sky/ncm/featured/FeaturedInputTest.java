package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.featured.FeaturedInput.Kind;
import org.junit.jupiter.api.Test;

class FeaturedInputTest {

    static void id(String input, String expected) {
        var p = FeaturedInput.parse(input);
        assertEquals(Kind.ID, p.kind(), input + " → " + p.message());
        assertEquals(expected, p.playlistId(), input);
    }

    static void error(String input, String code) {
        var p = FeaturedInput.parse(input);
        assertEquals(Kind.ERROR, p.kind(), input);
        assertEquals(code, p.error(), input);
        assertFalse(p.message() == null || p.message().isBlank());
    }

    @Test
    void plainIds() {
        id("24381616", "24381616");
        id("  24381616\n", "24381616");
        id("0024381616", "24381616");
        error("0", "unrecognized");
        error("", "empty");
        error("   ", "empty");
        error(null, "empty");
    }

    @Test
    void longLinks() {
        id("https://music.163.com/#/playlist?id=24381616", "24381616");
        id("https://music.163.com/playlist?id=24381616&userid=32953014", "24381616");
        id("http://music.163.com/m/playlist?id=123", "123");
        id("https://y.music.163.com/m/playlist?id=123&userid=1&creatorId=2", "123");
        id("https://music.163.com/#/my/m/music/playlist?id=456", "456");
        id("https://music.163.com/playlist/789/32953014/?userid=1", "789");
        id("HTTPS://MUSIC.163.COM/#/playlist?id=99", "99");
        id("music.163.com/playlist?id=42", "42");
    }

    @Test
    void shareText() {
        id("分享哈哈创建的歌单「深夜」: https://music.163.com/playlist?id=2468&userid=1 (@网易云音乐)", "2468");
        id("分享歌单：https://y.music.163.com/m/playlist?id=135，快来听", "135");
        var p = FeaturedInput.parse("分享哈哈创建的歌单「深夜」: https://163cn.tv/AbC123 (@网易云音乐)");
        assertEquals(Kind.SHORT, p.kind());
        assertEquals("AbC123", p.shortCode());
        assertEquals(Kind.SHORT, FeaturedInput.parse("163cn.tv/xyz9").kind());
        // 文案里先出现别的站点，后面才是网易云：取网易云的
        id("看看 https://example.com/a 和 https://music.163.com/playlist?id=77", "77");
    }

    @Test
    void otherTypesAreRejectedWithReason() {
        error("https://music.163.com/#/album?id=32311", "unsupported_type");
        error("https://music.163.com/song?id=186016", "unsupported_type");
        error("https://music.163.com/#/artist?id=6452", "unsupported_type");
        assertTrue(FeaturedInput.parse("https://music.163.com/#/album?id=1").message().contains("专辑"));
        error("https://music.163.com/#/discover", "unrecognized");
        error("https://music.163.com/#/playlist", "unrecognized");
    }

    @Test
    void foreignOrTrickyHostsAreRejected() {
        error("https://example.com/playlist?id=1", "not_netease");
        error("https://music.163.com.evil.com/playlist?id=1", "not_netease");
        error("https://evilmusic.163.com/playlist?id=1", "not_netease");
        error("https://music.163.com@evil.com/playlist?id=1", "not_netease");
        error("ftp://music.163.com/playlist?id=1", "unrecognized");
        error("javascript:alert(1)", "unrecognized");
        error("https://163cn.tv/../../etc", "bad_short");
        error("https://163cn.tv/a", "bad_short");
        error("x".repeat(FeaturedInput.MAX_INPUT + 1), "too_long");
        error("随便写点什么", "unrecognized");
    }

    @Test
    void shortLinkLocationIsValidated() {
        var ok = FeaturedInput.parseLocation("https://y.music.163.com/m/playlist?id=2468&userid=1&app_version=9");
        assertEquals(Kind.ID, ok.kind());
        assertEquals("2468", ok.playlistId());
        assertEquals("redirect_rejected", FeaturedInput.parseLocation("http://169.254.169.254/latest/meta-data").error());
        assertEquals("redirect_rejected", FeaturedInput.parseLocation("https://evil.com/playlist?id=1").error());
        assertEquals("redirect_rejected", FeaturedInput.parseLocation("/playlist?id=1").error());
        assertEquals("redirect_rejected", FeaturedInput.parseLocation(null).error());
        assertEquals("redirect_loop", FeaturedInput.parseLocation("https://163cn.tv/AbC123").error());
        assertEquals("unsupported_type", FeaturedInput.parseLocation("https://y.music.163.com/m/song?id=1").error());
    }
}

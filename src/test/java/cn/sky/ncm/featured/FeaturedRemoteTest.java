package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.more.ToolApis;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.featured.FeaturedInput.Kind;
import cn.sky.ncm.gateway.IdentityResolver;
import cn.sky.ncm.setting.SettingService;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** 短链展开的本地部分（不发请求）：代码校验和跳转地址校验。 */
class FeaturedRemoteTest {

    @Test
    void locationMustBeNeteasePlaylist() {
        var ok = FeaturedRemote.location("https://y.music.163.com/m/playlist?id=2468&userid=1");
        assertEquals(Kind.ID, ok.kind());
        assertEquals("2468", ok.playlistId());
        // 相对地址按 163cn.tv 解析，结果仍是短链：拒绝
        assertEquals("redirect_loop", FeaturedRemote.location("/AbCd12").error());
        assertEquals("redirect_rejected", FeaturedRemote.location("http://127.0.0.1:8090/apis/x").error());
        assertEquals("redirect_rejected", FeaturedRemote.location("https://evil.com/playlist?id=1").error());
        assertEquals("redirect_rejected", FeaturedRemote.location(null).error());
        assertEquals("redirect_rejected", FeaturedRemote.location("  ").error());
        assertEquals("unsupported_type", FeaturedRemote.location("https://y.music.163.com/m/song?id=1").error());
    }

    @Test
    void invalidCodeIsRejectedWithoutRequest() {
        var remote = new FeaturedRemote(mock(ApiRegistry.class), mock(ApiExecutor.class), mock(IdentityResolver.class),
            mock(SettingService.class), mock(NcmBootstrap.class), mock(ToolApis.class), mock(NcmHttp.class));
        StepVerifier.create(remote.expandShort("../etc"))
            .assertNext(p -> assertEquals("bad_short", p.error()))
            .verifyComplete();
        StepVerifier.create(remote.expandShort(null))
            .assertNext(p -> assertEquals("bad_short", p.error()))
            .verifyComplete();
    }
}

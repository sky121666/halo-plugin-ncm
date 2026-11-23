package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.finder.NcmFinderImpl;
import cn.sky.ncm.finder.vo.LyricLineVo;
import cn.sky.ncm.finder.vo.LyricVo;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.PlayerSetting;
import cn.sky.ncm.setting.SettingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class PlayerRouterTest {

    static LyricLineVo line(Long t, String text, String tr) {
        return LyricLineVo.builder().timeMs(t).text(text).translation(tr).build();
    }

    @Test
    void lyricsAndTranslationAreAvailableWithLegacyLyricOff() {
        var settings = mock(SettingService.class);
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        var legacyPlayer = JsonMapper.shared().readValue(
            "{\"lyric\":\"off\",\"resume\":false,\"mode\":\"single\",\"volume\":35}", PlayerSetting.class);
        when(settings.player()).thenReturn(Mono.just(legacyPlayer));

        var finder = mock(NcmFinderImpl.class);
        when(finder.strict()).thenReturn(finder);
        when(finder.lyric("1")).thenReturn(Mono.just(LyricVo.builder()
            .songId("1")
            .instrumental(false)
            .lines(List.of(line(1500L, "故事的小黄花", "Little yellow flowers")))
            .build()));
        var router = new PlayerRouter(settings, finder, new RateLimiter(), mock(PlayService.class));
        var web = WebTestClient.bindToRouterFunction(router.ncmPlayerRouter()).build();

        web.get().uri("/music/lyric/1").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.lines[0].t").isEqualTo(1500)
            .jsonPath("$.lines[0].text").isEqualTo("故事的小黄花")
            .jsonPath("$.lines[0].tr").isEqualTo("Little yellow flowers");
    }

    @Test
    void lyricJson() {
        LyricVo v = LyricVo.builder().songId("1").instrumental(false).lines(List.of(
            line(0L, "作词 : 方文山", null),
            line(1500L, "故事的小黄花", "Little yellow flowers"),
            line(null, "没有时间", null))).build();
        String json = Js.json(PlayerRouter.lyricJson("1", v));
        assertEquals("{\"id\":\"1\",\"instrumental\":false,\"lines\":[{\"t\":0,\"text\":\"作词 : 方文山\"},"
            + "{\"t\":1500,\"text\":\"故事的小黄花\",\"tr\":\"Little yellow flowers\"}]}", json);
    }

    @Test
    void instrumentalAndLimit() {
        List<LyricLineVo> many = new ArrayList<>();
        for (long i = 0; i < 1500; i++) {
            many.add(line(i, "l" + i, null));
        }
        Map<String, Object> m = PlayerRouter.lyricJson("2",
            LyricVo.builder().songId("2").instrumental(true).lines(many).build());
        assertTrue((Boolean) m.get("instrumental"));
        assertEquals(PlayerRouter.MAX_LINES, ((List<?>) m.get("lines")).size());
        Map<String, Object> e = PlayerRouter.empty("3");
        assertFalse((Boolean) e.get("instrumental"));
        assertTrue(((List<?>) e.get("lines")).isEmpty());
    }
}

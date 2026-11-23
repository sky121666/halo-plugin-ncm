package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.client.Js;
import cn.sky.ncm.finder.vo.AlbumBriefVo;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.UserBriefVo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class CardRouterTest {

    /** 测试用缩略图：在地址后面加上尺寸，便于确认用的是哪个尺寸。 */
    static final BiFunction<String, Integer, String> THUMB = (url, size) -> url + "?param=" + size + "y" + size;

    static SongVo song(String id, String availability) {
        return SongVo.builder()
            .id(id)
            .name("晴天" + id)
            .artistNames("周杰伦")
            .album(AlbumBriefVo.builder().id("18915").name("叶惠美").coverUrl("https://p1.music.126.net/a.jpg").build())
            .coverUrl("https://p1.music.126.net/a.jpg")
            .availability(availability)
            .playUrl("/music/play/" + id + "?level=standard")
            .build();
    }

    @Test
    void songCardJson() {
        String json = Js.json(CardRouter.songCard(song("186016", "full"), true, THUMB));
        assertEquals("{\"type\":\"song\",\"id\":\"186016\",\"title\":\"晴天186016\",\"sub\":\"周杰伦 · 叶惠美\","
            + "\"cover\":\"https://p1.music.126.net/a.jpg?param=200y200\",\"path\":\"/music/song/186016\","
            + "\"tracks\":[{\"id\":\"186016\",\"src\":\"/music/play/186016?level=standard\",\"title\":\"晴天186016\","
            + "\"artist\":\"周杰伦\",\"cover\":\"https://p1.music.126.net/a.jpg?param=160y160\","
            + "\"availability\":\"full\"}]}", json);
    }

    @Test
    void noPathWhenPageClosedAndNullsOmitted() {
        SongVo s = SongVo.builder().id("1").name("无封面").playUrl("/music/play/1?level=standard").build();
        Map<String, Object> m = CardRouter.songCard(s, false, THUMB);
        assertNull(m.get("path"));
        assertNull(m.get("cover"));
        assertNull(m.get("sub"));
        String json = Js.json(m);
        assertFalse(json.contains("\"path\""));
        assertFalse(json.contains("\"cover\""));
        assertTrue(json.contains("\"artist\":\"\""));
    }

    @Test
    void playlistLimitsTracksAndSkipsBadOnes() {
        List<SongVo> tracks = new ArrayList<>();
        tracks.add(SongVo.builder().id("0").name("没有播放地址").build());
        for (int i = 1; i <= 80; i++) {
            tracks.add(song(String.valueOf(i), i % 2 == 0 ? "trial" : null));
        }
        PlaylistVo p = PlaylistVo.builder()
            .id("24381616").name("我喜欢的音乐").coverUrl("https://p2.music.126.net/c.jpg")
            .trackCount(321).creator(UserBriefVo.builder().nickname("站长").build())
            .tracks(tracks).build();
        Map<String, Object> m = CardRouter.playlistCard(p, true, THUMB);
        assertEquals("playlist", m.get("type"));
        assertEquals("站长 · 321 首", m.get("sub"));
        assertEquals("/music/playlist/24381616", m.get("path"));
        assertEquals(321, m.get("total"));
        List<?> list = (List<?>) m.get("tracks");
        assertEquals(CardRouter.MAX_TRACKS, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("1", first.get("id"));
        assertNull(first.get("availability"));
        assertEquals("trial", ((Map<?, ?>) list.get(1)).get("availability"));
    }

    @Test
    void albumCard() {
        AlbumVo a = AlbumVo.builder().id("18915").name("叶惠美").artistNames("周杰伦").publishDate("2003-07-31")
            .tracks(List.of(song("186016", "full"))).build();
        Map<String, Object> m = CardRouter.albumCard(a, true, THUMB);
        assertEquals("周杰伦 · 2003-07-31", m.get("sub"));
        assertEquals(1, m.get("total"));
        assertEquals("/music/album/18915", m.get("path"));
        assertEquals(1, ((List<?>) m.get("tracks")).size());
        // 没有歌曲列表时 tracks 为空数组，不是 null
        Map<String, Object> empty = CardRouter.albumCard(AlbumVo.builder().id("2").name("空").build(), true, THUMB);
        assertTrue(((List<?>) empty.get("tracks")).isEmpty());
        assertNull(empty.get("total"));
    }

    @Test
    void errorBody() {
        assertEquals("{\"type\":\"song\",\"id\":\"1\",\"error\":\"unavailable\"}",
            Js.json(CardRouter.error("song", "1", "unavailable")));
    }

    @Test
    void joinNonBlank() {
        assertEquals("a · b", CardRouter.joinNonBlank(" · ", " a ", null, "", "b"));
        assertNull(CardRouter.joinNonBlank(" · ", null, " "));
    }
}

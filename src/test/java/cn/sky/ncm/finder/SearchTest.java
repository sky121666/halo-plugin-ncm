package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.finder.vo.HotKeywordVo;
import cn.sky.ncm.finder.vo.SearchResultVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTest {

    static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s.replace('\'', '"'));
    }

    @Test
    void types() {
        assertEquals(SearchType.SONG, SearchType.of(null));
        assertEquals(SearchType.SONG, SearchType.of("mv"));
        assertEquals(SearchType.ALBUM, SearchType.of(" Album "));
        assertEquals(1000, SearchType.of("playlist").code());
        assertEquals(100, SearchType.of("artist").code());
    }

    @Test
    void normalizeKeyword() {
        assertNull(NcmFinderImpl.normalizeKeyword(null));
        assertNull(NcmFinderImpl.normalizeKeyword("  \t "));
        assertEquals("周杰伦 晴天", NcmFinderImpl.normalizeKeyword("  周杰伦\n\n 晴天 "));
        assertEquals(100, NcmFinderImpl.normalizeKeyword("歌".repeat(100)).length());
        assertNull(NcmFinderImpl.normalizeKeyword("歌".repeat(101)));
        assertEquals(34, NcmFinderImpl.maxSearchPage(30));
    }

    @Test
    void songs() throws Exception {
        SearchResultVo r = NcmVoMapper.search(json("{'code':200,'result':{'songCount':321,'songs':["
            + "{'id':186016,'name':'晴天','ar':[{'id':6452,'name':'周杰伦'}],'al':{'id':18905,'name':'叶惠美',"
            + "'picUrl':'http://p1.music.126.net/x.jpg'},'dt':269000,'fee':8,'privilege':{'id':186016,'st':0,'pl':128000}},"
            + "{'name':'no id'}]}}"), "晴天", SearchType.SONG, 1, 30);
        assertEquals("song", r.getType());
        assertEquals(321, r.getTotal());
        assertEquals(1, r.getSongs().size());
        assertEquals("周杰伦", r.getSongs().get(0).getArtistNames());
        assertTrue(r.getSongs().get(0).getPlayable());
        assertTrue(r.getPlaylists().isEmpty());
        assertFalse(r.isEmpty());
    }

    @Test
    void playlistsAlbumsArtists() throws Exception {
        SearchResultVo p = NcmVoMapper.search(json("{'result':{'playlistCount':2,'playlists':[{'id':1,'name':'P',"
            + "'coverImgUrl':'http://p1.music.126.net/c.jpg','trackCount':9,'playCount':5,'creator':{'userId':7,'nickname':'N'}}]}}"),
            "p", SearchType.PLAYLIST, 1, 30);
        assertEquals("N", p.getPlaylists().get(0).getCreator().getNickname());
        assertEquals(List.of(), p.getPlaylists().get(0).getTracks());

        SearchResultVo a = NcmVoMapper.search(json("{'result':{'albumCount':1,'albums':[{'id':3,'name':'A',"
            + "'picUrl':'https://p1.music.126.net/a.jpg','artists':[{'id':4,'name':'S'}],'publishTime':1059580800000,'size':10}]}}"),
            "a", SearchType.ALBUM, 1, 30);
        assertEquals("S", a.getAlbums().get(0).getArtistNames());
        assertEquals("2003-07-31", a.getAlbums().get(0).getPublishDate());

        SearchResultVo ar = NcmVoMapper.search(json("{'result':{'artistCount':1,'artists':[{'id':6452,'name':'周杰伦',"
            + "'alias':['Jay Chou'],'picUrl':'http://p1.music.126.net/j.jpg','albumSize':30}]}}"),
            "j", SearchType.ARTIST, 1, 30);
        assertEquals("Jay Chou", ar.getArtists().get(0).getAliases().get(0));
        assertEquals(List.of(), ar.getArtists().get(0).getHotSongs());
    }

    @Test
    void emptyAndTotalFloor() throws Exception {
        SearchResultVo e = NcmVoMapper.search(json("{'result':{}}"), "x", SearchType.SONG, 1, 30);
        assertTrue(e.isEmpty());
        assertEquals(0, e.getTotal());
        // 网易云没给总数时，至少等于已经翻过的条数
        SearchResultVo f = NcmVoMapper.search(json("{'result':{'songs':[{'id':1,'name':'a'}]}}"), "x",
            SearchType.SONG, 3, 30);
        assertEquals(61, f.getTotal());
        assertTrue(NcmVoMapper.emptySearch("x", SearchType.ALBUM, 1, 30).isEmpty());
    }

    @Test
    void hotKeywords() throws Exception {
        List<HotKeywordVo> list = NcmVoMapper.hotKeywords(json("{'data':[{'searchWord':'晴天','content':'','score':99,"
            + "'iconUrl':'http://p1.music.126.net/i.png'},{'searchWord':' '},{'searchWord':'稻香','content':'好听'}]}"), 10);
        assertEquals(2, list.size());
        assertNull(list.get(0).getDescription());
        assertEquals("https://p1.music.126.net/i.png", list.get(0).getIconUrl());
        assertEquals("好听", list.get(1).getDescription());
        assertEquals(1, NcmVoMapper.hotKeywords(json("{'data':[{'searchWord':'a'},{'searchWord':'b'}]}"), 1).size());
    }
}

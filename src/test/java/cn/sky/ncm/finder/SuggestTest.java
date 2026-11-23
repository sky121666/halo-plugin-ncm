package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.finder.vo.SuggestItemVo;
import cn.sky.ncm.finder.vo.SuggestVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SuggestTest {

    static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s.replace('\'', '"'));
    }

    static final String BODY = "{'code':200,'result':{"
        + "'albums':[{'id':18905,'name':'叶惠美','artist':{'id':6452,'name':'周杰伦'},'picUrl':'http://p1.music.126.net/a.jpg'}],"
        + "'artists':[{'id':6452,'name':'周杰伦','alias':['Jay Chou'],'img1v1Url':'http://p1.music.126.net/j.jpg'}],"
        + "'songs':[{'id':186016,'name':'晴天','artists':[{'id':6452,'name':'周杰伦'}],'album':{'id':18905,'name':'叶惠美'}},"
        + "{'name':'没有 id'},{'id':'abc','name':'坏 id'}],"
        + "'playlists':[{'id':1,'name':'周杰伦精选','coverImgUrl':'http://p1.music.126.net/p.jpg','trackCount':42}],"
        + "'order':['songs','artists','albums','playlists']}}";

    @Test
    void groupsInOrder() throws Exception {
        SuggestVo v = NcmVoMapper.suggest(json(BODY), "周杰伦", 5);
        assertEquals(4, v.getGroups().size());
        assertEquals("song", v.getGroups().get(0).getType());
        assertEquals("单曲", v.getGroups().get(0).getLabel());
        assertEquals(1, v.getGroups().get(0).getItems().size());

        SuggestItemVo song = v.getGroups().get(0).getItems().get(0);
        assertEquals("周杰伦 · 叶惠美", song.getSub());
        assertEquals("/music/song/186016", song.getPath());
        assertNull(song.getCoverUrl());

        SuggestItemVo artist = v.getGroups().get(1).getItems().get(0);
        assertEquals("Jay Chou", artist.getSub());
        assertEquals("https://p1.music.126.net/j.jpg", artist.getCoverUrl());

        SuggestItemVo album = v.getGroups().get(2).getItems().get(0);
        assertEquals("周杰伦", album.getSub());
        assertEquals("/music/album/18905", album.getPath());

        SuggestItemVo pl = v.getGroups().get(3).getItems().get(0);
        assertEquals("42 首", pl.getSub());
        assertEquals("/music/playlist/1", pl.getPath());
    }

    @Test
    void missingOrderAndEmptyGroups() throws Exception {
        SuggestVo v = NcmVoMapper.suggest(json("{'result':{'playlists':[{'id':2,'name':'p'}],'songs':[]}}"), "p", 5);
        assertEquals(1, v.getGroups().size());
        assertEquals("playlist", v.getGroups().get(0).getType());
        assertNull(v.getGroups().get(0).getItems().get(0).getSub());
        assertTrue(NcmVoMapper.suggest(json("{'result':{}}"), "x", 5).isEmpty());
        assertTrue(NcmVoMapper.suggest(json("{'code':400}"), "x", 5).isEmpty());
    }

    @Test
    void perGroupLimit() throws Exception {
        StringBuilder songs = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            songs.append(i > 1 ? "," : "").append("{'id':").append(i).append(",'name':'s").append(i).append("'}");
        }
        SuggestVo v = NcmVoMapper.suggest(json("{'result':{'songs':[" + songs + "]}}"), "s", 5);
        assertEquals(5, v.getGroups().get(0).getItems().size());
    }
}

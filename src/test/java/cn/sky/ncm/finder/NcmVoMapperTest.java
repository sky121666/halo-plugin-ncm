package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NcmVoMapperTest {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode json(String s) throws Exception {
        return M.readTree(s.replace('\'', '"'));
    }

    @Test
    void songDetail() throws Exception {
        JsonNode body = json("{'songs':[{'id':186016,'name':'晴天','alia':[],'tns':['Sunny Day'],"
            + "'ar':[{'id':6452,'name':'周杰伦'}],'al':{'id':18905,'name':'叶惠美','picUrl':'http://p1.music.126.net/x.jpg'},"
            + "'dt':269000,'fee':8,'mv':504177}],"
            + "'privileges':[{'id':186016,'st':0,'pl':128000,'fee':8}]}");
        var songs = NcmVoMapper.songs(body);
        assertEquals(1, songs.size());
        var s = songs.get(0);
        assertEquals("186016", s.getId());
        assertEquals("周杰伦", s.getArtistNames());
        assertEquals("https://p1.music.126.net/x.jpg", s.getCoverUrl());
        assertEquals("4:29", s.getDurationText());
        assertEquals("Sunny Day", s.getTranslations().get(0));
        assertTrue(s.getPlayable());
        assertFalse(s.getVip());
        assertEquals("504177", s.getMvId());
        assertEquals("/music/play/186016?level=standard", s.getPlayUrl());
    }

    @Test
    void availabilityIsOnlyAHint() throws Exception {
        assertEquals("full", NcmVoMapper.availability(json("{'st':0,'pl':128000}"), 0));
        // 会员歌曲：游客 pl = 0，但往往有试听片段，不能当成不可播放
        assertEquals("trial", NcmVoMapper.availability(json("{'st':0,'pl':0,'fee':1}"), 1));
        assertEquals("trial", NcmVoMapper.availability(json("{'st':0,'pl':0,'fee':8,"
            + "'freeTrialPrivilege':{'resConsumable':true,'userConsumable':false}}"), 8));
        assertEquals("unavailable", NcmVoMapper.availability(json("{'st':-200,'pl':0,'fee':1}"), 1));
        assertEquals("unavailable", NcmVoMapper.availability(json("{'st':0,'pl':0,'fee':0}"), 0));
        assertNull(NcmVoMapper.availability(null, 0));
        JsonNode body = json("{'songs':[{'id':7,'name':'借过一下 (Live)','ar':[],'al':{},'dt':0,'fee':1}],"
            + "'privileges':[{'id':7,'st':0,'pl':0,'fee':1}]}");
        assertEquals("trial", NcmVoMapper.songs(body).get(0).getAvailability());
    }

    @Test
    void vipSongNotPlayableForGuest() throws Exception {
        JsonNode body = json("{'songs':[{'id':1,'name':'x','ar':[],'al':{},'dt':0,'fee':1,'mv':0}],"
            + "'privileges':[{'id':1,'st':0,'pl':0}]}");
        var s = NcmVoMapper.songs(body).get(0);
        assertTrue(s.getVip());
        assertFalse(s.getPlayable());
        assertNull(s.getMvId());
        assertEquals("", s.getDurationText());
    }

    @Test
    void legacySongShape() throws Exception {
        JsonNode s = json("{'id':2,'name':'y','artists':[{'id':3,'name':'A'},{'id':4,'name':'B'}],"
            + "'album':{'id':5,'name':'Al','picUrl':'https://p2.music.126.net/a.jpg'},'duration':61000}");
        var vo = NcmVoMapper.song(s, null);
        assertEquals("A / B", vo.getArtistNames());
        assertEquals("1:01", vo.getDurationText());
        assertNull(vo.getPlayable());
    }

    @Test
    void thumbnailAndPlayUrl() {
        assertEquals("https://p1.music.126.net/x.jpg?param=200y200",
            NcmVoMapper.thumbnail("http://p1.music.126.net/x.jpg?param=50y50", 200));
        assertEquals("https://p1.music.126.net/x.jpg?param=1024y1024",
            NcmVoMapper.thumbnail("https://p1.music.126.net/x.jpg", 99999));
        assertEquals("https://example.com/a.png", NcmVoMapper.thumbnail("https://example.com/a.png", 100));
        assertEquals("", NcmVoMapper.thumbnail(null, 100));
        assertEquals("/music/play/1?level=standard",
            NcmVoMapper.playUrl("1", "<script>"));
        assertEquals("/music/play/1?level=lossless",
            NcmVoMapper.playUrl("1", "lossless"));
    }

    @Test
    void playlistSummariesMarkOwnAndLiked() throws Exception {
        JsonNode body = json("{'playlist':[{'id':10,'name':'我喜欢的音乐','specialType':5,'creator':{'userId':99}},"
            + "{'id':11,'name':'收藏的','specialType':0,'creator':{'userId':1}}]}");
        var list = NcmVoMapper.playlistSummaries(body, "99");
        assertTrue(list.get(0).getOwn());
        assertTrue(list.get(0).getLikedSongs());
        assertFalse(list.get(1).getOwn());
    }

    @Test
    void lyricInstrumental() throws Exception {
        var vo = NcmVoMapper.lyric("1", json("{'pureMusic':true,'lrc':{'lyric':'[00:00.00]纯音乐，请欣赏'}}"));
        assertTrue(vo.getInstrumental());
        assertFalse(vo.getHasTranslation());
    }
}

package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.api.more.ToolApis.SongUrl;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.finder.FeaturedBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturedCheckTest {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode json(String s) throws Exception {
        return M.readTree(s.replace('\'', '"'));
    }

    static NcmResponse r(int status, String body) throws Exception {
        return new NcmResponse(status, json(body), List.of());
    }

    static final String PUBLIC = "{'code':200,'playlist':{'id':1,'name':'深夜','coverImgUrl':'http://p1.music.126.net/a.jpg',"
        + "'trackCount':3,'privacy':0,'creator':{'userId':7,'nickname':'站长'}}}";
    static final String PRIVATE = "{'code':200,'playlist':{'id':1,'name':'私藏','trackCount':3,'privacy':10}}";

    @Test
    void publicPlaylist() throws Exception {
        var a = FeaturedCheck.access(r(200, PUBLIC), null);
        assertEquals(FeaturedCheck.OK, a.state());
        assertEquals("深夜", a.playlist().getName());
        assertEquals("站长", a.playlist().getCreator().getNickname());
        assertNull(a.message());
    }

    @Test
    void privateAndMissing() throws Exception {
        // 游客直接拿到了私密歌单（例如站长身份）：也不能公开
        assertEquals(FeaturedCheck.PRIVATE, FeaturedCheck.access(r(200, PRIVATE), null).state());
        // 游客看不到、站长看得到：私密
        var a = FeaturedCheck.access(r(404, "{'code':404,'message':'无权限'}"), r(200, PRIVATE));
        assertEquals(FeaturedCheck.PRIVATE, a.state());
        assertEquals("私藏", a.playlist().getName());
        // 都看不到：不存在
        assertEquals(FeaturedCheck.MISSING, FeaturedCheck.access(r(404, "{'code':404}"), r(404, "{'code':404}")).state());
        // 插件把业务码 400 记成了 HTTP 200
        assertEquals(FeaturedCheck.MISSING, FeaturedCheck.access(r(200, "{'code':400,'msg':'参数错误'}"), null).state());
        assertEquals(FeaturedCheck.MISSING, FeaturedCheck.access(r(200, "{'code':20001}"), null).state());
    }

    @Test
    void transientErrorsArePendingNotMissing() throws Exception {
        var a = FeaturedCheck.access(r(503, "{'code':503,'msg':'xeapi 公钥尚未获取'}"), null);
        assertEquals(FeaturedCheck.ERROR, a.state());
        assertTrue(a.message().contains("503"));
        assertEquals(FeaturedCheck.ERROR, FeaturedCheck.access(r(429, "{}"), null).state());
        assertEquals(FeaturedCheck.ERROR, FeaturedCheck.access(r(200, "{'code':502}"), null).state());
        assertEquals(FeaturedCheck.ERROR, FeaturedCheck.access(null, null).state());
        // 游客出错时不拿站长结果下结论
        assertEquals(FeaturedCheck.ERROR, FeaturedCheck.access(r(500, "{}"), r(200, PRIVATE)).state());
    }

    @Test
    void stats() throws Exception {
        JsonNode body = json("{'songs':[{'id':1,'name':'a','fee':8},{'id':2,'name':'b','fee':1},"
            + "{'id':3,'name':'c','fee':0},{'id':4,'name':'d','fee':8}],"
            + "'privileges':[{'id':1,'st':0,'pl':128000},{'id':2,'st':0,'pl':0,'fee':1},{'id':3,'st':-200,'pl':0}]}");
        var s = FeaturedCheck.stats(FeaturedBridge.songs(body), 4);
        assertEquals(1, s.getFull());
        assertEquals(1, s.getTrial());
        assertEquals(1, s.getUnavailable());
        assertEquals(1, s.getPending(), "没有版权信息的算待确认");
        assertFalse(s.getTruncated());
        // 请求失败：全部待确认
        var failed = FeaturedCheck.stats(null, 30);
        assertEquals(30, failed.getPending());
        assertEquals(0, failed.getFull());
        // 超过 1000 首只统计前 1000 首
        var many = new java.util.ArrayList<cn.sky.ncm.finder.vo.SongVo>();
        for (int i = 0; i < FeaturedCheck.MAX_COUNTED; i++) {
            many.add(cn.sky.ncm.finder.vo.SongVo.builder().id(String.valueOf(i)).availability("full").build());
        }
        var big = FeaturedCheck.stats(many, 1500);
        assertTrue(big.getTruncated());
        assertEquals(1500, big.getTotal());
        assertEquals(1000, big.getCounted());
    }

    @Test
    void measured() throws Exception {
        var ok = r(200, "{}");
        assertEquals("full", FeaturedCheck.measured(new SongUrl(ok, "https://m701.music.126.net/a.mp3",
            json("{'freeTrialInfo':null}"))));
        assertEquals("trial", FeaturedCheck.measured(new SongUrl(ok, "https://m701.music.126.net/a.mp3",
            json("{'freeTrialInfo':{'start':0,'end':30}}"))));
        var unavailable = json("{'code':404,'url':null}");
        assertEquals("unavailable", FeaturedCheck.measured(new SongUrl(ok, null, unavailable)));
        assertEquals("pending", FeaturedCheck.measured(new SongUrl(ok, null, null)));
        assertEquals("pending", FeaturedCheck.measured(new SongUrl(r(200, "{'code':502}"), null, unavailable)));
        // 取址失败、限流：待确认，不算不可播
        assertEquals("pending", FeaturedCheck.measured(new SongUrl(r(429, "{}"), null, null)));
        assertEquals("pending", FeaturedCheck.measured(new SongUrl(null, null, null)));
    }

    @Test
    void songsOfRejectsEmptyShellAndBusinessFailure() throws Exception {
        String ok = "{'code':200,'songs':[{'id':1,'name':'a'}],'privileges':[{'id':1,'st':0,'pl':128000}]}";
        assertEquals(1, FeaturedCheck.songsOf(r(200, ok), 1).size());
        // 歌单有曲目，返回却是空列表：空壳
        assertEquals(null, FeaturedCheck.songsOf(r(200, "{'code':200,'songs':[],'privileges':[]}"), 3));
        // 空歌单本来就没有曲目：正常
        assertEquals(0, FeaturedCheck.songsOf(r(200, "{'code':200,'songs':[]}"), 0).size());
        // 业务失败、没有 songs 数组、HTTP 失败
        assertEquals(null, FeaturedCheck.songsOf(r(200, "{'code':-460,'msg':'Cheating'}"), 3));
        assertEquals(null, FeaturedCheck.songsOf(r(200, "{'code':200}"), 3));
        assertEquals(null, FeaturedCheck.songsOf(r(503, "{'code':503}"), 3));
        assertEquals(null, FeaturedCheck.songsOf(null, 3));
        // 交给统计时全部算待确认
        var s = FeaturedCheck.stats(FeaturedCheck.songsOf(r(200, "{'code':200,'songs':[]}"), 3), 3);
        assertEquals(3, s.getPending());
        assertEquals(0, s.getFull());
    }
}

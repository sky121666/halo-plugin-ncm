package cn.sky.ncm.finder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LrcParserTest {

    @Test
    void parsesTimestampsAndMergesTranslation() {
        String lrc = String.join("\n",
            "{\"t\":0,\"c\":[{\"tx\":\"作词: \"}]}",
            "[ti:晴天]",
            "[00:01.5]第一句",
            "[00:12.34][01:02.345]重复的一句",
            "[00:20.00]",
            "没有时间标签的行");
        String tlrc = "[00:12.34]A repeated line\n";
        var lines = LrcParser.merge(lrc, tlrc);
        assertEquals(4, lines.size());
        assertEquals(1500L, lines.get(0).getTimeMs());
        assertEquals("第一句", lines.get(0).getText());
        assertNull(lines.get(0).getTranslation());
        assertEquals(12340L, lines.get(1).getTimeMs());
        assertEquals("A repeated line", lines.get(1).getTranslation());
        assertEquals("00:12.34", lines.get(1).getTimeText());
        assertEquals("", lines.get(2).getText());
        assertEquals(62345L, lines.get(3).getTimeMs());
        assertEquals("重复的一句", lines.get(3).getText());
    }

    @Test
    void emptyInput() {
        assertEquals(0, LrcParser.merge(null, null).size());
        assertEquals(0, LrcParser.merge("  ", "").size());
    }
}

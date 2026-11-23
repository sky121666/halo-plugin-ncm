package cn.sky.ncm.me;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebInputException;

class MeParamsTest {

    @Test
    void idAllowsOnlyOneToTwentyDigits() {
        assertEquals("123", MeParams.id(" 123 ", "id"));
        assertEquals("12345678901234567890", MeParams.id("12345678901234567890", "id"));
        for (String bad : new String[] {null, "", "abc", "-1", "1.5", "1,2", "123456789012345678901", "1 2", "１２"}) {
            assertThrows(ServerWebInputException.class, () -> MeParams.id(bad, "id"), String.valueOf(bad));
        }
    }

    @Test
    void idListDeduplicatesAndLimits() {
        assertEquals(List.of("1", "2", "3"), MeParams.idList("1, 2,,3,1", "ids", 200));
        assertThrows(ServerWebInputException.class, () -> MeParams.idList("", "ids", 200));
        assertThrows(ServerWebInputException.class, () -> MeParams.idList("1,x", "ids", 200));

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            sb.append(i).append(',');
        }
        assertThrows(ServerWebInputException.class, () -> MeParams.idList(sb.toString(), "ids", 200));
        // 重复的 ID 不计数
        assertEquals(1, MeParams.idList(List.of("7", "7", "7"), "songIds", 2).size());
        assertThrows(ServerWebInputException.class, () -> MeParams.idList(List.of(), "songIds", 100));
        assertThrows(ServerWebInputException.class, () -> MeParams.idList((List<String>) null, "songIds", 100));
    }

    @Test
    void pagingDefaultsAndBounds() {
        assertEquals(0, MeParams.offset(Optional.empty()));
        assertEquals(30, MeParams.limit(Optional.empty()));
        assertEquals(100, MeParams.limit(Optional.of("100")));
        assertEquals(20, MeParams.offset(Optional.of(" 20 ")));
        for (String bad : new String[] {"0", "101", "-1", "abc", "9999999999"}) {
            assertThrows(ServerWebInputException.class, () -> MeParams.limit(Optional.of(bad)), bad);
        }
        assertThrows(ServerWebInputException.class, () -> MeParams.offset(Optional.of("100001")));
    }

    @Test
    void playlistNameAndPrivacy() {
        assertEquals("夜里听的歌", MeParams.playlistName("  夜里听的歌 "));
        assertEquals("😀".repeat(40), MeParams.playlistName("😀".repeat(40)));
        assertThrows(ServerWebInputException.class, () -> MeParams.playlistName("   "));
        assertThrows(ServerWebInputException.class, () -> MeParams.playlistName(null));
        assertThrows(ServerWebInputException.class, () -> MeParams.playlistName("a".repeat(41)));
        assertThrows(ServerWebInputException.class, () -> MeParams.playlistName("第一行\n第二行"));

        assertEquals(0, MeParams.privacy(null));
        assertEquals(10, MeParams.privacy(10));
        assertThrows(ServerWebInputException.class, () -> MeParams.privacy(1));
    }

    @Test
    void rankType() {
        assertEquals("week", MeParams.rankType(Optional.empty()));
        assertEquals("all", MeParams.rankType(Optional.of("all")));
        assertThrows(ServerWebInputException.class, () -> MeParams.rankType(Optional.of("month")));
    }
}

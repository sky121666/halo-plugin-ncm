package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.setting.PageSetting;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PageSettingTest {

    @Test
    void defaults() {
        assertTrue(PageSetting.DEFAULT.open());
        assertTrue(PageSetting.DEFAULT.searchOpen());
        assertFalse(PageSetting.CLOSED.open());
        assertFalse(PageSetting.CLOSED.searchOpen());
        PageSetting s = new PageSetting(null, " ", null, null, null, null, null, null, null);
        assertFalse(s.open());
        assertFalse(s.searchOpen());
        assertEquals("网抑云", PageSetting.DEFAULT.titleOrDefault());
        assertEquals("网抑云", s.titleOrDefault());
        assertEquals("", s.descriptionOrEmpty());
        assertEquals(50, s.size());
        assertEquals(20, s.searchLimit());
        assertEquals(List.of(), s.featuredIds());
    }

    @Test
    void searchFollowsPageSwitch() {
        assertTrue(new PageSetting(true, null, null, null, null, null, null, null, null).searchOpen());
        assertFalse(new PageSetting(true, null, null, null, null, false, null, null, null).searchOpen());
        assertFalse(new PageSetting(false, null, null, null, null, true, null, null, null).searchOpen());
        assertEquals(1, new PageSetting(true, null, null, null, null, true, 0, null, null).searchLimit());
        assertEquals(600, new PageSetting(true, null, null, null, null, true, 9999, null, null).searchLimit());
    }

    @Test
    void suggestFollowsSearchSwitch() {
        assertTrue(PageSetting.DEFAULT.suggestOpen());
        assertEquals(60, PageSetting.DEFAULT.suggestLimit());
        assertFalse(PageSetting.CLOSED.suggestOpen());
        assertTrue(new PageSetting(true, null, null, null, null, null, null, null, null).suggestOpen());
        assertFalse(new PageSetting(true, null, null, null, null, false, null, true, null).suggestOpen());
        assertFalse(new PageSetting(true, null, null, null, null, true, null, false, null).suggestOpen());
        assertEquals(600, new PageSetting(true, null, null, null, null, true, null, true, 9999).suggestLimit());
    }

    @Test
    void savedNavigationMarkerDoesNotAffectPageSettings() {
        var page = JsonMapper.shared().readValue(
            "{\"enabled\":true,\"searchEnabled\":false,\"featuredPlaylistIds\":\"17\","
                + "\"themeMarkers\":\"data-theme-swup\"}", PageSetting.class);
        assertTrue(page.open());
        assertFalse(page.searchOpen());
        assertEquals(List.of("17"), page.featuredIds());
    }

    @Test
    void featuredIdsAreValidatedAndLimited() {
        PageSetting s = new PageSetting(true, "歌", "", "1, 2，abc 3,2,,99999999999999999999999", 5, true, 20, null, null);
        assertEquals(List.of("1", "2", "3"), s.featuredIds());
        assertEquals(10, s.size());
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            many.append(i).append(',');
        }
        assertEquals(12, new PageSetting(true, null, null, many.toString(), 999, true, 20, null, null).featuredIds().size());
        assertEquals(200, new PageSetting(true, null, null, null, 999, true, 20, null, null).size());
    }
}

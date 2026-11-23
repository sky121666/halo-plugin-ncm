package cn.sky.ncm.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.setting.PlayerSetting;
import org.junit.jupiter.api.Test;

class PlayerSettingTest {

    @Test
    void defaults() {
        PlayerSetting d = PlayerSetting.DEFAULT;
        assertTrue(d.resumeOn());
    }

    @Test
    void modeAndVolume() {
        PlayerSetting d = PlayerSetting.DEFAULT;
        assertEquals("loop", d.playMode());
        assertEquals(100, d.volumePercent());
        PlayerSetting bad = new PlayerSetting("random", 250, null);
        assertEquals("loop", bad.playMode());
        assertEquals(100, bad.volumePercent());
        PlayerSetting s = new PlayerSetting("shuffle", -5, false);
        assertEquals("shuffle", s.playMode());
        assertFalse(s.resumeOn());
        assertEquals(0, s.volumePercent());
    }

}

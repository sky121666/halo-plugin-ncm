package cn.sky.ncm.setting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;

/** Folia 播放默认值。升级时忽略已保存的旧播放器字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerSetting(String mode, Integer volume, Boolean resume) {

    public static final PlayerSetting DEFAULT = new PlayerSetting("loop", 100, true);

    static final Set<String> MODES = Set.of("loop", "order", "single", "shuffle");

    public boolean resumeOn() {
        return !Boolean.FALSE.equals(resume);
    }

    public String playMode() {
        return mode != null && MODES.contains(mode) ? mode : "loop";
    }

    public int volumePercent() {
        return volume == null ? 100 : Math.max(0, Math.min(volume, 100));
    }
}

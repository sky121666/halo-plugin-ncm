package cn.sky.ncm.setting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 插件设置「音乐页面」分组。没保存过时用 {@link #DEFAULT}（页面开启）；读取出错时用 {@link #CLOSED}（页面关闭，返回 404）。
 *
 * @param enabled             是否开启 /music 页面
 * @param title               页面标题
 * @param description         页面简介
 * @param featuredPlaylistIds 首页推荐的歌单 ID，英文逗号分隔，最多 12 个
 * @param pageSize            歌单页每页歌曲数（10~200）
 * @param searchEnabled       是否开启 /music/search 搜索页
 * @param searchPerMinute     每个 IP 每分钟最多搜索次数（1~600）
 * @param suggestEnabled      是否开启搜索联想（输入时的下拉提示）
 * @param suggestPerMinute    每个 IP 每分钟最多联想请求数（1~600）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageSetting(Boolean enabled, String title, String description, String featuredPlaylistIds,
                          Integer pageSize, Boolean searchEnabled, Integer searchPerMinute,
                          Boolean suggestEnabled, Integer suggestPerMinute) {

    static final Pattern ID = Pattern.compile("^\\d{1,20}$");

    public static final PageSetting DEFAULT = new PageSetting(true, "音乐", "", "", 50, true, 20, true, 60);
    public static final PageSetting CLOSED = new PageSetting(false, "音乐", "", "", 50, false, 20, false, 60);

    public boolean open() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 页面开启且搜索开启（没填时视为开启）。 */
    public boolean searchOpen() {
        return open() && !Boolean.FALSE.equals(searchEnabled);
    }

    /** 搜索页开启且联想开启（没填时视为开启）。 */
    public boolean suggestOpen() {
        return searchOpen() && !Boolean.FALSE.equals(suggestEnabled);
    }

    public int suggestLimit() {
        return suggestPerMinute == null ? 60 : Math.max(1, Math.min(suggestPerMinute, 600));
    }

    public int searchLimit() {
        return searchPerMinute == null ? 20 : Math.max(1, Math.min(searchPerMinute, 600));
    }

    public String titleOrDefault() {
        return title == null || title.isBlank() ? "音乐" : title.strip();
    }

    public String descriptionOrEmpty() {
        return description == null ? "" : description.strip();
    }

    public List<String> featuredIds() {
        if (featuredPlaylistIds == null || featuredPlaylistIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(featuredPlaylistIds.split("[,，\\s]+"))
            .map(String::trim)
            .filter(s -> ID.matcher(s).matches())
            .distinct()
            .limit(12)
            .toList();
    }

    public int size() {
        return pageSize == null ? 50 : Math.max(10, Math.min(pageSize, 200));
    }
}

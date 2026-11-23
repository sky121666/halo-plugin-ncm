package cn.sky.ncm.finder;

import java.util.Locale;

/** 搜索类型。key 用在网址和模板里（?type=song），code 是网易云 cloudsearch 的 type。 */
public enum SearchType {
    SONG("song", "单曲", 1, "songs", "songCount"),
    PLAYLIST("playlist", "歌单", 1000, "playlists", "playlistCount"),
    ALBUM("album", "专辑", 10, "albums", "albumCount"),
    ARTIST("artist", "歌手", 100, "artists", "artistCount");

    private final String key;
    private final String label;
    private final int code;
    private final String listField;
    private final String countField;

    SearchType(String key, String label, int code, String listField, String countField) {
        this.key = key;
        this.label = label;
        this.code = code;
        this.listField = listField;
        this.countField = countField;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public int code() {
        return code;
    }

    String listField() {
        return listField;
    }

    String countField() {
        return countField;
    }

    /** 不认识的值按单曲处理。 */
    public static SearchType of(String key) {
        if (key != null) {
            String k = key.trim().toLowerCase(Locale.ROOT);
            for (SearchType t : values()) {
                if (t.key.equals(k)) {
                    return t;
                }
            }
        }
        return SONG;
    }
}

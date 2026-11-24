package cn.sky.ncm.setting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 控制 ncmFinder 展示哪些游客可读的站长数据。
 * 读取失败时一律关闭（{@link #CLOSED}），不会因为配置异常而意外公开个人数据。
 *
 * @param siteProfile   公开站长的网易云资料（昵称、头像、签名、等级）
 * @param sitePlaylists 公开站长的歌单（只含网易云上本来就公开的歌单）
 * @param siteRank      展示允许游客查看的站长听歌排行
 * @param cacheSeconds  Finder 结果缓存秒数，最少 60
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinderSetting(Boolean siteProfile, Boolean sitePlaylists, Boolean siteRank, Integer cacheSeconds) {

    /** 默认公开资料和歌单。 */
    public static final FinderSetting DEFAULT = new FinderSetting(true, true, false, 600);

    /** 读取失败时使用。 */
    public static final FinderSetting CLOSED = new FinderSetting(false, false, false, 600);

    public boolean profileOpen() {
        return Boolean.TRUE.equals(siteProfile);
    }

    public boolean playlistsOpen() {
        return Boolean.TRUE.equals(sitePlaylists);
    }

    public boolean rankOpen() {
        return Boolean.TRUE.equals(siteRank);
    }

    public int cacheTtl() {
        return cacheSeconds == null ? 600 : Math.max(60, cacheSeconds);
    }
}

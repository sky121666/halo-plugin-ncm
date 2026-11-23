package cn.sky.ncm.finder;

import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 给歌单发布功能（cn.sky.ncm.featured）用的数据转换入口，复用 {@link NcmVoMapper} 的规则，
 * 保证后台预览和 /music 页面对同一份数据的理解一致。
 */
public final class FeaturedBridge {

    private FeaturedBridge() {
    }

    /** playlist_detail 的返回里有没有歌单。 */
    public static boolean hasPlaylist(JsonNode detailBody) {
        return detailBody != null && NcmVoMapper.hasPlaylist(detailBody);
    }

    /** playlist_detail 的返回转成 PlaylistVo（不含曲目）。 */
    public static PlaylistVo playlist(JsonNode detailBody) {
        return NcmVoMapper.playlist(detailBody, List.of());
    }

    /** playlist_track_all / song_detail 的返回转成歌曲列表（带 availability 预估）。 */
    public static List<SongVo> songs(JsonNode tracksBody) {
        return tracksBody == null ? List.of() : NcmVoMapper.songs(tracksBody);
    }

    public static String thumbnail(String url, Integer size) {
        return NcmVoMapper.thumbnail(url, size);
    }
}

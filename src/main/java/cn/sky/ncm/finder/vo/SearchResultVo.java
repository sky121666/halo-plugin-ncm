package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * 搜索结果。只有和 type 对应的那个列表有内容，其余为空列表。
 * 歌单、专辑、歌手的结果不含歌曲（tracks / hotSongs 为空列表）。
 */
@Value
@Builder
public class SearchResultVo {
    /** 搜索词（已去掉首尾空白） */
    String keyword;
    /** song / playlist / album / artist */
    String type;
    /** 第几页，从 1 开始 */
    int page;
    /** 每页条数 */
    int size;
    /** 网易云给出的结果总数 */
    long total;
    List<SongVo> songs;
    List<PlaylistVo> playlists;
    List<AlbumVo> albums;
    List<ArtistVo> artists;

    /** 本页是否没有结果。 */
    public boolean isEmpty() {
        return switch (type == null ? "song" : type) {
            case "playlist" -> playlists == null || playlists.isEmpty();
            case "album" -> albums == null || albums.isEmpty();
            case "artist" -> artists == null || artists.isEmpty();
            default -> songs == null || songs.isEmpty();
        };
    }
}

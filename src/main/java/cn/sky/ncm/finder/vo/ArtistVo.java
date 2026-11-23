package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 歌手详情 */
@Value
@Builder
public class ArtistVo {

    /** 歌手 ID */
    String id;

    /** 歌手名 */
    String name;

    /** 别名 */
    List<String> aliases;

    /** 头像地址（https） */
    String coverUrl;

    /** 简介 */
    String briefDesc;

    /** 专辑数 */
    Integer albumSize;

    /** 歌曲数 */
    Integer musicSize;

    /** MV 数 */
    Integer mvSize;

    /** 热门歌曲（按 limit 截取） */
    List<SongVo> hotSongs;

    /** 网易云网页地址 */
    String url;
}

package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 歌单（列表用） */
@Value
@Builder
public class PlaylistSummaryVo {

    /** 歌单 ID */
    String id;

    /** 歌单名 */
    String name;

    /** 封面地址（https） */
    String coverUrl;

    /** 歌曲总数 */
    Integer trackCount;

    /** 播放次数 */
    Long playCount;

    /** 是否为站长自己创建（否则为收藏） */
    Boolean own;

    /** 是否为“我喜欢的音乐” */
    Boolean likedSongs;

    /** 网易云网页地址 */
    String url;
}

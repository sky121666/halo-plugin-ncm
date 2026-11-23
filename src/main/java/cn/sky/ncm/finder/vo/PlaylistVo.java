package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 歌单详情 */
@Value
@Builder
public class PlaylistVo {

    /** 歌单 ID */
    String id;

    /** 歌单名 */
    String name;

    /** 封面地址（https） */
    String coverUrl;

    /** 简介 */
    String description;

    /** 标签 */
    List<String> tags;

    /** 歌曲总数 */
    Integer trackCount;

    /** 播放次数 */
    Long playCount;

    /** 创建者 */
    UserBriefVo creator;

    /** 歌曲（按 limit 截取） */
    List<SongVo> tracks;

    /** 网易云网页地址 */
    String url;
}

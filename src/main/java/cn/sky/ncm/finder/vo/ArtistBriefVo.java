package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 歌手（简要） */
@Value
@Builder
public class ArtistBriefVo {

    /** 歌手 ID */
    String id;

    /** 歌手名 */
    String name;
}

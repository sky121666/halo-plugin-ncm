package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 专辑（简要） */
@Value
@Builder
public class AlbumBriefVo {

    /** 专辑 ID */
    String id;

    /** 专辑名 */
    String name;

    /** 封面地址（https） */
    String coverUrl;
}

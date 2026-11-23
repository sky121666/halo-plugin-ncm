package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 一行歌词 */
@Value
@Builder
public class LyricLineVo {

    /** 时间（毫秒） */
    Long timeMs;

    /** 时间，例如 01:23.45 */
    String timeText;

    /** 歌词 */
    String text;

    /** 译文，没有时为 null */
    String translation;
}

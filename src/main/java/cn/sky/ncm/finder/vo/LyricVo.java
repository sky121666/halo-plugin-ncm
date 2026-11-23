package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 歌词 */
@Value
@Builder
public class LyricVo {

    /** 歌曲 ID */
    String songId;

    /** 按时间排序的歌词行（已合并译文） */
    List<LyricLineVo> lines;

    /** 原始 LRC 歌词 */
    String lyric;

    /** 原始 LRC 译文，没有时为 null */
    String translatedLyric;

    /** 是否有译文 */
    Boolean hasTranslation;

    /** 是否纯音乐（没有歌词） */
    Boolean instrumental;
}

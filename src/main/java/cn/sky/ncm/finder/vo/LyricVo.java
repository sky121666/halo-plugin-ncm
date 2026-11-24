package cn.sky.ncm.finder.vo;

import java.util.List;
import java.util.Map;
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

    /** 供 Folia 解析的原始歌词格式，缺失的格式不写入。 */
    Map<String, String> formats;

    /** 是否有译文 */
    Boolean hasTranslation;

    /** 是否纯音乐（没有歌词） */
    Boolean instrumental;
}

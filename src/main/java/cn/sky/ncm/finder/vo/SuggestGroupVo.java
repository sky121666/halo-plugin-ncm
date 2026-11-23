package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 搜索联想的一组（同一类型）。 */
@Value
@Builder
public class SuggestGroupVo {
    /** song / artist / album / playlist */
    String type;
    /** 单曲 / 歌手 / 专辑 / 歌单 */
    String label;
    List<SuggestItemVo> items;
}

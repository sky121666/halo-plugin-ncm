package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 热搜词。 */
@Value
@Builder
public class HotKeywordVo {
    /** 搜索词 */
    String keyword;
    /** 说明文字，可能为空 */
    String description;
    /** 热度 */
    long score;
    /** 标签图标（新、热等），可能为空 */
    String iconUrl;
}

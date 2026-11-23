package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 搜索联想的一项。 */
@Value
@Builder
public class SuggestItemVo {
    /** song / artist / album / playlist */
    String type;
    String id;
    /** 名称 */
    String name;
    /** 补充说明：歌曲为歌手 · 专辑，专辑为歌手，歌单为歌曲数，歌手为别名；可能为空 */
    String sub;
    /** 封面，可能为空 */
    String coverUrl;
    /** 站内页面路径，例如 /music/song/186016 */
    String path;
}

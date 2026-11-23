package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 歌曲 */
@Value
@Builder
public class SongVo {

    /** 歌曲 ID */
    String id;

    /** 歌名 */
    String name;

    /** 别名，例如电影主题曲说明；没有时为空列表 */
    List<String> aliases;

    /** 译名；没有时为空列表 */
    List<String> translations;

    /** 歌手 */
    List<ArtistBriefVo> artists;

    /** 歌手名，用“ / ”连接 */
    String artistNames;

    /** 所属专辑 */
    AlbumBriefVo album;

    /** 封面地址（专辑封面，https） */
    String coverUrl;

    /** 时长（毫秒） */
    Long durationMs;

    /** 时长，例如 4:29 */
    String durationText;

    /** 收费类型：0/8 免费，1 会员，4 数字专辑 */
    Integer fee;

    /** 是否会员歌曲（fee = 1） */
    Boolean vip;

    /**
     * 按渲染页面时使用的身份（游客或站长）是否有完整播放权限；没有版权信息时为 null。
     * 注意：false 不代表不能播放（可能有试听片段，访客也可能用自己绑定的账号），不要据此禁用播放按钮，见 availability。
     */
    Boolean playable;

    /**
     * 按版权信息预估的可播放情况，只用于提示，实际以播放时取址的结果为准：
     * full 可以完整播放 / trial 可能只能试听 / unavailable 可能无法播放 / null 没有版权信息。
     */
    String availability;

    /** MV ID，没有 MV 时为 null */
    String mvId;

    /** 网易云网页地址 */
    String url;

    /** 插件提供的播放地址（302 跳转到音频，标准音质），可直接用作 audio 的 src */
    String playUrl;
}

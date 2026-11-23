package cn.sky.ncm.featured;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 发布到 /music 首页的歌单（后台「网易云音乐 → 歌单发布」管理）。
 * <p>
 * metadata.name 固定为 {@code playlist-{网易云歌单 ID}}，同一个歌单只能添加一次，迁移重复执行也不会产生重复记录。
 * 只有 spec.published = true 并且游客能公开访问的歌单才会出现在 /music。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "ncm.halo.run", version = "v1alpha1", kind = "NcmFeaturedPlaylist",
    plural = "ncmfeaturedplaylists", singular = "ncmfeaturedplaylist")
public class NcmFeaturedPlaylist extends AbstractExtension {

    public static final String NAME_PREFIX = "playlist-";

    private Spec spec;

    private Status status;

    public static String nameOf(String playlistId) {
        return NAME_PREFIX + playlistId;
    }

    @Data
    public static class Spec {
        /** 网易云歌单 ID */
        private String playlistId;

        /** 显示标题，空则用网易云的歌单名 */
        private String title;

        /** 显示简介，空则用网易云的简介 */
        private String description;

        /** 显示封面，空则用网易云的封面（预留，后台暂不提供编辑） */
        private String coverUrl;

        /** 排序，越小越靠前 */
        private Integer priority;

        /** 是否在 /music 首页显示（false 为草稿或已下架） */
        private Boolean published;

        /** 歌单页是否对访客隐藏不可播的歌（预留，默认 false：保留并标明状态） */
        private Boolean hideUnavailable;
    }

    @Data
    public static class Status {
        /**
         * 最近一次核对的结果：ok 游客可公开访问 / private 私密或访客无权访问 / missing 已删除或不存在 /
         * error 核对失败（网络、限流等，状态待确认）/ unchecked 还没有核对过
         */
        private String state;

        /** 最近一次核对时的歌单名 */
        private String name;

        private String creator;

        private String coverUrl;

        private Integer trackCount;

        /** 游客视角的可播放预估 */
        private Stats guest;

        /** 站长账号视角的可播放预估；没绑定站长账号时为 null */
        private Stats site;

        /** 最近一次核对时间（ISO-8601） */
        private String checkedAt;

        /** 最近一次核对的说明（不能公开的原因或错误），没有时为 null */
        private String lastError;

        /** 歌单已被删除、设为私密或访客无权访问 */
        private Boolean missing;
    }

    /** 按版权信息（privileges）预估的可播放数量；pending 为没有版权信息或核对失败的数量（待确认）。 */
    @Data
    public static class Stats {
        private Integer full;
        private Integer trial;
        private Integer unavailable;
        private Integer pending;
        /** 实际统计的曲目数（最多 1000） */
        private Integer counted;
        /** 歌单总曲目数 */
        private Integer total;
        /** 曲目数超过 1000，只统计了前 1000 首 */
        private Boolean truncated;
    }
}

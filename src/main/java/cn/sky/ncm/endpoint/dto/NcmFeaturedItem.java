package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 后台列表里的一个发布歌单 */
@Data
@Schema(description = "后台列表里的一个发布歌单")
public class NcmFeaturedItem {

    @Schema(description = "记录名，固定为 playlist-{歌单 ID}", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "网易云歌单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String playlistId;

    @Schema(description = "站长填写的显示标题，空为使用网易云的")
    private String title;

    @Schema(description = "站长填写的简介，空为使用网易云的")
    private String description;

    @Schema(description = "实际显示的标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String displayTitle;

    @Schema(description = "是否已发布", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean published;

    @Schema(description = "排序，越小越靠前", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer priority;

    @Schema(description = "最近一次核对结果：ok / private / missing / error / unchecked", requiredMode = Schema.RequiredMode.REQUIRED)
    private String state;

    @Schema(description = "访客能不能在 /music 看到（已发布并且最近一次确认可公开）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean visible;

    @Schema(description = "最近一次核对时间")
    private String checkedAt;

    @Schema(description = "不能公开的原因或核对错误")
    private String lastError;

    @Schema(description = "网易云上的歌单名")
    private String neteaseName;

    @Schema(description = "创建者昵称")
    private String creator;

    @Schema(description = "封面缩略图")
    private String coverUrl;

    @Schema(description = "曲目数")
    private Integer trackCount;

    @Schema(description = "游客视角的可播放预估")
    private NcmFeaturedStats guest;

    @Schema(description = "站长账号视角的可播放预估")
    private NcmFeaturedStats site;

    @Schema(description = "添加时间")
    private String createdAt;
}

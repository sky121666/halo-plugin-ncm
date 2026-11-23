package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/** 歌单预览（不保存） */
@Data
@Schema(description = "歌单预览（不保存）")
public class NcmFeaturedPreview {

    @Schema(description = "网易云歌单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String playlistId;

    @Schema(description = "能否公开：ok / private / missing / error", requiredMode = Schema.RequiredMode.REQUIRED)
    private String state;

    @Schema(description = "不能公开的原因或核对错误")
    private String message;

    @Schema(description = "歌单名")
    private String name;

    @Schema(description = "创建者昵称")
    private String creator;

    @Schema(description = "封面缩略图")
    private String coverUrl;

    @Schema(description = "网易云上的简介")
    private String description;

    @Schema(description = "曲目数")
    private Integer trackCount;

    @Schema(description = "是否绑定了站长账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean siteBound;

    @Schema(description = "游客视角的可播放预估")
    private NcmFeaturedStats guest;

    @Schema(description = "站长账号视角的可播放预估")
    private NcmFeaturedStats site;

    @Schema(description = "前 50 首", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmFeaturedTrack> tracks;

    @Schema(description = "是否做了游客抽样实测", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean probed;

    @Schema(description = "已经添加过时的记录")
    private NcmFeaturedItem existing;
}

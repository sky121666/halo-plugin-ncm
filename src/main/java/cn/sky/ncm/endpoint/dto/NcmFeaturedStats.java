package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 按版权信息预估的可播放数量（静态预估，以实际播放为准） */
@Data
@Schema(description = "按版权信息预估的可播放数量（静态预估，以实际播放为准）")
public class NcmFeaturedStats {

    @Schema(description = "可以完整播放", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer full;

    @Schema(description = "可能只能试听", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer trial;

    @Schema(description = "可能无法播放", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer unavailable;

    @Schema(description = "待确认：没有版权信息、曲目返回为空或核对失败", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer pending;

    @Schema(description = "实际统计的曲目数（最多 1000）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer counted;

    @Schema(description = "歌单总曲目数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer total;

    @Schema(description = "曲目超过 1000 首，只统计了前 1000 首", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean truncated;
}

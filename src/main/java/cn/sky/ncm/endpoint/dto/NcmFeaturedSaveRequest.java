package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 新建或编辑发布歌单 */
@Data
@Schema(description = "新建或编辑发布歌单")
public class NcmFeaturedSaveRequest {

    @Schema(description = "网易云歌单 ID（新建时必填，编辑时忽略）")
    private String playlistId;

    @Schema(description = "显示标题，留空使用网易云的（最多 80 字）")
    private String title;

    @Schema(description = "简介，留空使用网易云的（最多 500 字）")
    private String description;

    @Schema(description = "新建后是否直接发布（编辑时忽略）")
    private Boolean publish;
}

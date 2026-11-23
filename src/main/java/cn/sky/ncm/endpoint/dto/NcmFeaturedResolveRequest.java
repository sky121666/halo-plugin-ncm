package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 识别粘贴的内容 */
@Data
@Schema(description = "识别粘贴的内容")
public class NcmFeaturedResolveRequest {

    @Schema(description = "网易云歌单分享链接、分享文案或歌单 ID（最多 2000 字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String input;
}

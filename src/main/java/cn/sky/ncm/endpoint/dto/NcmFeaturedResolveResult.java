package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 识别结果 */
@Data
@Schema(description = "识别结果")
public class NcmFeaturedResolveResult {

    @Schema(description = "是否识别出歌单", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean ok;

    @Schema(description = "歌单 ID")
    private String playlistId;

    @Schema(description = "错误代码：empty / too_long / unrecognized / not_netease / unsupported_type / invalid_url / "
        + "bad_short / short_failed / redirect_loop / redirect_rejected")
    private String error;

    @Schema(description = "给站长看的说明")
    private String message;

    @Schema(description = "已经添加过时的记录名")
    private String existing;
}

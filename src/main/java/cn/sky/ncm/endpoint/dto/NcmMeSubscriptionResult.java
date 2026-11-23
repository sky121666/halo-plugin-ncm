package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 收藏 / 取消收藏歌单的结果 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "收藏 / 取消收藏歌单的结果")
public class NcmMeSubscriptionResult {

    @Schema(description = "歌单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String playlistId;

    @Schema(description = "操作后的状态：true 已收藏，false 已取消", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean subscribed;

    @Schema(description = "查询时表示是否为本人创建的歌单")
    private Boolean own;
}

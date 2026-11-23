package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 红心 / 取消红心的结果 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "红心 / 取消红心的结果")
public class NcmMeLikeResult {

    @Schema(description = "歌曲 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String songId;

    @Schema(description = "操作后的状态：true 已红心，false 已取消", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean liked;
}

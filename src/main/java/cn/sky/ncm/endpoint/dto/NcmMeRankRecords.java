package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 听歌排行 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "听歌排行")
public class NcmMeRankRecords {

    @Schema(description = "week（最近一周）或 all（所有时间）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    @Schema(description = "排行，按网易云返回的顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmMeRecord> items;
}

package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条听歌记录 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "一条听歌记录")
public class NcmMeRecord {

    @Schema(description = "歌曲", requiredMode = Schema.RequiredMode.REQUIRED)
    private NcmMeSong song;

    @Schema(description = "最近播放时间（ISO 8601），仅最近播放有")
    private String playedAt;

    @Schema(description = "播放次数，仅听歌排行有")
    private Integer playCount;

    @Schema(description = "排行分数（0~100），仅听歌排行有")
    private Integer score;
}

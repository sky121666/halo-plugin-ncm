package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 加入 / 移出歌单的结果 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "加入 / 移出歌单的结果")
public class NcmMeTracksResult {

    @Schema(description = "歌单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String playlistId;

    @Schema(description = "add（加入）或 del（移出）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String op;

    @Schema(description = "本次处理的歌曲 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> songIds;
}

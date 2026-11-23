package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 最近播放的歌单 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "最近播放的歌单")
public class NcmMePlaylistRecord {

    @Schema(description = "歌单", requiredMode = Schema.RequiredMode.REQUIRED)
    private NcmMePlaylist playlist;

    @Schema(description = "最近播放时间（ISO 8601）")
    private String playedAt;
}

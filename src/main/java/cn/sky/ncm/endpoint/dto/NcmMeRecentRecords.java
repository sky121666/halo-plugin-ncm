package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 最近播放 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "最近播放")
public class NcmMeRecentRecords {

    @Schema(description = "最近播放的歌曲", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmMeRecord> songs;

    @Schema(description = "最近播放的歌单", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmMePlaylistRecord> playlists;

    @Schema(description = "最近播放的歌单暂时无法读取，可重试")
    private boolean playlistsUnavailable;
}

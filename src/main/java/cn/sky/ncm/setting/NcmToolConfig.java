package cn.sky.ncm.setting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"revision", "settings", "playlists"})
public record NcmToolConfig(Long revision, NcmToolSettings settings, List<PlaylistChoice> playlists) {
    @Schema(name = "NcmMiniPlayerPlaylistChoice", requiredProperties = {"id", "title"})
    public record PlaylistChoice(String id, String title) {
    }
}

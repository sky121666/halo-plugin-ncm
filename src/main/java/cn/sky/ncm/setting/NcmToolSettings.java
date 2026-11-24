package cn.sky.ncm.setting;

import cn.sky.ncm.featured.FeaturedInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.server.ServerWebInputException;

@Schema(requiredProperties = {"ai", "miniPlayer"})
public record NcmToolSettings(Ai ai, MiniPlayer miniPlayer) {
    @Schema(name = "NcmToolAiSettings", requiredProperties = {"enabled", "audience", "allowedRoles"})
    public record Ai(Boolean enabled, String audience, List<String> allowedRoles) {
        public static final Ai DEFAULT = new Ai(true, "signed-in", List.of());
        static final Ai CLOSED = new Ai(false, "roles", List.of());
    }

    @Schema(name = "NcmMiniPlayerSettings", requiredProperties = {"enabled", "playlistId"})
    public record MiniPlayer(Boolean enabled, String playlistId) {
        static final MiniPlayer DEFAULT = new MiniPlayer(false, "");
    }

    void validateForSave() {
        if (ai == null || ai.enabled() == null || ai.audience() == null || ai.allowedRoles() == null
            || miniPlayer == null || miniPlayer.enabled() == null || miniPlayer.playlistId() == null) {
            throw new ServerWebInputException("工具设置不完整");
        }
        if (!Set.of("signed-in", "roles").contains(ai.audience())
            || ai.allowedRoles().size() > 100 || ai.allowedRoles().stream().anyMatch(Objects::isNull)) {
            throw new ServerWebInputException("音乐 AI 使用范围无效");
        }
        String playlist = miniPlayer.playlistId();
        if ((Boolean.TRUE.equals(miniPlayer.enabled()) || !playlist.isEmpty()) && !FeaturedInput.validId(playlist)) {
            throw new ServerWebInputException("请选择已发布的精选歌单");
        }
    }
}

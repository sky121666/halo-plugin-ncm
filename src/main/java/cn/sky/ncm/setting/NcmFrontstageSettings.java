package cn.sky.ncm.setting;

import static java.util.Objects.requireNonNullElse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.web.server.ServerWebInputException;

/** 音乐页设置的存储和接口字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(requiredProperties = {"enabled", "title", "description", "pageSize", "searchEnabled", "suggestEnabled",
    "siteProfile", "sitePlaylists", "siteRank", "mode", "volume", "resume"})
public record NcmFrontstageSettings(Boolean enabled, String title, String description, Integer pageSize,
                                  Boolean searchEnabled, Boolean suggestEnabled,
                                  Boolean siteProfile, Boolean sitePlaylists, Boolean siteRank, String mode, Integer volume,
                                  Boolean resume, NcmMusicPageMetadata metadata) {

    public NcmFrontstageSettings(Boolean enabled, String title, String description, Integer pageSize,
                                 Boolean searchEnabled, Boolean suggestEnabled, Boolean siteProfile,
                                 Boolean sitePlaylists, Boolean siteRank, String mode, Integer volume, Boolean resume) {
        this(enabled, title, description, pageSize, searchEnabled, suggestEnabled, siteProfile, sitePlaylists,
            siteRank, mode, volume, resume, null);
    }

    static NcmFrontstageSettings fromLegacy(PageSetting page, FinderSetting finder, PlayerSetting player) {
        return new NcmFrontstageSettings(page.open(), requireNonNullElse(page.title(), PageSetting.DEFAULT_TITLE),
            requireNonNullElse(page.description(), ""), requireNonNullElse(page.pageSize(), 50),
            !Boolean.FALSE.equals(page.searchEnabled()), !Boolean.FALSE.equals(page.suggestEnabled()),
            finder.profileOpen(), finder.playlistsOpen(), finder.rankOpen(),
            requireNonNullElse(player.mode(), PlayerSetting.DEFAULT.mode()),
            requireNonNullElse(player.volume(), PlayerSetting.DEFAULT.volume()), player.resumeOn(), page.metadata());
    }

    PageSetting page(AdvancedSetting advanced) {
        return new PageSetting(enabled, title, description, null, pageSize, searchEnabled,
            advanced.musicSearchPerMinute(), suggestEnabled, advanced.musicSuggestPerMinute(), metadata);
    }

    FinderSetting finder(AdvancedSetting advanced) {
        return new FinderSetting(siteProfile, sitePlaylists, siteRank, advanced.siteContentCacheSeconds());
    }

    PlayerSetting player() {
        return new PlayerSetting(mode, volume, resume);
    }

    NcmFrontstageSettings withStoredDefaults(NcmFrontstageSettings stored) {
        var savedMetadata = metadata == null ? stored.metadata()
            : metadata.withStoredDefaults(requireNonNullElse(stored.metadata(), NcmMusicPageMetadata.DEFAULT));
        return new NcmFrontstageSettings(enabled, title, description, pageSize, searchEnabled, suggestEnabled,
            siteProfile, sitePlaylists, siteRank, stored.mode(), stored.volume(),
            resume, savedMetadata);
    }

    void validateForSave() {
        if (Stream.of(enabled, title, description, pageSize, searchEnabled, suggestEnabled, siteProfile,
                sitePlaylists, siteRank, mode, volume, resume).anyMatch(Objects::isNull)) {
            throw new ServerWebInputException("音乐页设置不完整");
        }
        if (pageSize < 10 || pageSize > 200) {
            throw new ServerWebInputException("曲目加载数量须为 10–200");
        }
        if (!PlayerSetting.MODES.contains(mode) || volume < 0 || volume > 100) {
            throw new ServerWebInputException("默认播放模式或音量无效");
        }
        if (metadata != null) {
            metadata.validateForSave();
        }
    }
}

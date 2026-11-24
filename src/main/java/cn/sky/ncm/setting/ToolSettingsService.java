package cn.sky.ncm.setting;

import static cn.sky.ncm.setting.VersionedSettingsStore.readGroup;

import cn.sky.ncm.featured.FeaturedService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 工具设置独立保存，Halo 原生网络表单保存或重置时不会覆盖它。 */
@Component
public class ToolSettingsService {
    static final String CONFIG = "plugin-ncm-tool-configmap";
    static final String LEGACY_AI = "plugin-ncm-ai-configmap";
    static final String LEGACY_MIGRATED = "ncm.halo.run/ai-settings-migrated";
    private static final JsonMapper JSON = JsonMapper.shared();
    private final ReactiveExtensionClient client;
    private final FeaturedService featured;
    private final FrontstageSettingsService frontstage;
    private final VersionedSettingsStore<NcmToolSettings> store;

    public ToolSettingsService(ReactiveExtensionClient client, FeaturedService featured,
                               FrontstageSettingsService frontstage) {
        this.client = client;
        this.featured = featured;
        this.frontstage = frontstage;
        this.store = new VersionedSettingsStore<>(client, CONFIG, "tool-settings", "网抑云工具设置",
            NcmToolSettings.class);
    }

    public Mono<Void> initialize() {
        return store.initialize(() -> legacyAi()
            .map(ai -> new NcmToolSettings(ai, NcmToolSettings.MiniPlayer.DEFAULT)));
    }

    public Mono<NcmToolSettings> current() {
        return store.current();
    }

    public Mono<NcmToolConfig> read() {
        return store.read().flatMap(this::snapshot);
    }

    public Mono<NcmToolConfig> save(NcmToolConfig request) {
        return Mono.defer(() -> {
            if (request.revision() == null || request.settings() == null) {
                return Mono.error(new ServerWebInputException("缺少配置版本或工具设置"));
            }
            var settings = request.settings();
            settings.validateForSave();
            return store.save(request.revision(), settings,
                current -> validateMiniPlayer(current.miniPlayer(), settings.miniPlayer())).flatMap(this::snapshot);
        });
    }

    private Mono<Void> validateMiniPlayer(NcmToolSettings.MiniPlayer current, NcmToolSettings.MiniPlayer requested) {
        if (!Boolean.TRUE.equals(requested.enabled()) || requested.equals(current)) {
            return Mono.empty();
        }
        return frontstage.current().filter(settings -> Boolean.TRUE.equals(settings.enabled()))
            .switchIfEmpty(Mono.error(new ServerWebInputException("请先开启音乐页")))
            .flatMap(settings -> featured.publishedPlaylist(requested.playlistId()))
            .switchIfEmpty(Mono.error(new ServerWebInputException("所选歌单未发布或暂不可公开访问")))
            .then();
    }

    private Mono<NcmToolConfig> snapshot(VersionedSettingsStore.Snapshot<NcmToolSettings> saved) {
        return featured.publishedPlaylists().map(row -> {
            var title = row.getSpec().getTitle();
            if (title == null || title.isBlank()) title = row.getStatus().getName();
            return new NcmToolConfig.PlaylistChoice(row.getSpec().getPlaylistId(),
                Objects.requireNonNullElse(title, row.getSpec().getPlaylistId()));
        }).collectList().map(rows -> new NcmToolConfig(saved.revision(), saved.settings(), rows));
    }

    private Mono<NcmToolSettings.Ai> legacyAi() {
        return client.fetch(ConfigMap.class, ProxySettings.CONFIG)
            .flatMap(config -> {
                var group = aiGroup(config);
                var options = readAi(group);
                var annotations = config.getMetadata().getAnnotations();
                if (!group.isObject() || group.has("enabled")
                    || (annotations != null && "true".equals(annotations.get(LEGACY_MIGRATED)))) {
                    return Mono.just(options);
                }
                return client.fetch(ConfigMap.class, LEGACY_AI)
                    .map(old -> aiGroup(old).path("enabled"))
                    .filter(JsonNode::isBoolean)
                    .map(enabled -> new NcmToolSettings.Ai(enabled.asBoolean(), options.audience(), options.allowedRoles()))
                    .defaultIfEmpty(options);
            }).defaultIfEmpty(NcmToolSettings.Ai.DEFAULT);
    }

    private static JsonNode aiGroup(ConfigMap config) {
        try {
            return readGroup(config.getData(), "ai", JsonNode.class, JSON.createObjectNode());
        } catch (IllegalStateException error) {
            return JSON.getNodeFactory().nullNode();
        }
    }

    static NcmToolSettings.Ai readAi(JsonNode group) {
        if (!group.isObject()) return NcmToolSettings.Ai.CLOSED;
        var enabled = group.path("enabled");
        var audience = group.path("audience");
        boolean signedIn = audience.isMissingNode() || "signed-in".equals(audience.asText());
        var allowed = new ArrayList<String>();
        if ((signedIn || "roles".equals(audience.asText())) && group.path("allowedRoles").isArray()) {
            for (var role : group.path("allowedRoles")) {
                if (role.isTextual()) allowed.add(role.asText());
            }
        }
        return new NcmToolSettings.Ai(enabled.isMissingNode() || (enabled.isBoolean() && enabled.asBoolean()),
            signedIn ? "signed-in" : "roles", List.copyOf(allowed));
    }
}

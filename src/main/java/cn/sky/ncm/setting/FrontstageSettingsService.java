package cn.sky.ncm.setting;

import static cn.sky.ncm.setting.VersionedSettingsStore.readGroup;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 音乐页设置由工具页保存，技术配置继续由 Halo 原生 Setting 保存。 */
@Component
public class FrontstageSettingsService {

    static final String CONFIG = "plugin-ncm-frontstage-configmap";
    static final String SETTINGS = VersionedSettingsStore.SETTINGS;
    private static final JsonMapper JSON = JsonMapper.shared();
    private final ReactiveExtensionClient client;
    private final VersionedSettingsStore<NcmFrontstageSettings> store;

    public FrontstageSettingsService(ReactiveExtensionClient client) {
        this.client = client;
        this.store = new VersionedSettingsStore<>(client, CONFIG, "frontstage-settings", "音乐页设置",
            NcmFrontstageSettings.class);
    }

    public Mono<Void> initialize() {
        return store.initialize(() -> technicalConfig().map(this::fromLegacy))
            .then(Mono.defer(this::migrateTechnicalSettings));
    }

    public Mono<NcmFrontstageSettings> current() {
        return store.current();
    }

    public Mono<NcmFrontstageConfig> read() {
        return store.read().map(this::snapshot);
    }

    public Mono<PlayerSetting> legacyPlayer() {
        return store.read().map(saved -> saved.settings().player());
    }

    public Mono<NcmFrontstageConfig> save(NcmFrontstageConfig request) {
        return Mono.defer(() -> {
            if (request.revision() == null || request.settings() == null) {
                return Mono.error(new ServerWebInputException("缺少配置版本或音乐页设置"));
            }
            return store.read().flatMap(stored -> {
                // 保留旧播放器字段用于兼容与回滚。站点播放偏好由独立发布接口写入。
                var settings = request.settings().withStoredDefaults(stored.settings());
                settings.validateForSave();
                return store.save(request.revision(), settings).map(this::snapshot);
            });
        });
    }

    private NcmFrontstageSettings fromLegacy(ConfigMap legacy) {
        var data = legacy.getData() == null ? Map.<String, String>of() : legacy.getData();
        var page = readGroup(data, "page", PageSetting.class, PageSetting.DEFAULT);
        var finder = readGroup(data, "finder", FinderSetting.class, FinderSetting.DEFAULT);
        var player = readGroup(data, "player", PlayerSetting.class, PlayerSetting.DEFAULT);
        return NcmFrontstageSettings.fromLegacy(page, finder, player);
    }

    private Mono<Void> migrateTechnicalSettings() {
        return Mono.defer(() -> technicalConfig().flatMap(config -> {
            var data = config.getData() == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(config.getData());
            var existing = readGroup(data, "advanced", ObjectNode.class, JSON.createObjectNode());
            var updated = existing.deepCopy();
            var oldPage = readGroup(data, "page", ObjectNode.class, JSON.createObjectNode());
            var oldFinder = readGroup(data, "finder", ObjectNode.class, JSON.createObjectNode());
            migrateNumber(updated, "musicSearchPerMinute", oldPage, "searchPerMinute", PageSetting.DEFAULT.searchLimit());
            migrateNumber(updated, "musicSuggestPerMinute", oldPage, "suggestPerMinute", PageSetting.DEFAULT.suggestLimit());
            migrateNumber(updated, "siteContentCacheSeconds", oldFinder, "cacheSeconds", FinderSetting.DEFAULT.cacheTtl());
            if (updated.equals(existing)) {
                return Mono.empty();
            }
            data.put("advanced", updated.toString());
            config.setData(data);
            return client.update(config);
        })).retryWhen(Retry.backoff(3, Duration.ofMillis(50))
            .filter(OptimisticLockingFailureException.class::isInstance)).then();
    }

    private static void migrateNumber(ObjectNode target, String newKey, ObjectNode legacy, String oldKey, int fallback) {
        if (target.hasNonNull(newKey)) {
            return;
        }
        if (legacy.hasNonNull(oldKey)) {
            target.set(newKey, legacy.get(oldKey));
        } else {
            target.put(newKey, fallback);
        }
    }

    private NcmFrontstageConfig snapshot(VersionedSettingsStore.Snapshot<NcmFrontstageSettings> saved) {
        return new NcmFrontstageConfig(saved.revision(), saved.settings());
    }

    private Mono<ConfigMap> technicalConfig() {
        return client.fetch(ConfigMap.class, ProxySettings.CONFIG)
            .switchIfEmpty(Mono.error(new IllegalStateException("网抑云配置资源尚未初始化")));
    }
}

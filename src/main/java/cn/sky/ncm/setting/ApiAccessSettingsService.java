package cn.sky.ncm.setting;

import static cn.sky.ncm.setting.VersionedSettingsStore.readGroup;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class ApiAccessSettingsService {

    static final String CONFIG = "plugin-ncm-api-access-configmap";
    private final ReactiveExtensionClient client;
    private final VersionedSettingsStore<NcmApiAccessSettings> store;

    public ApiAccessSettingsService(ReactiveExtensionClient client) {
        this.client = client;
        this.store = new VersionedSettingsStore<>(client, CONFIG, "api-access-settings", "接口开放设置",
            NcmApiAccessSettings.class);
    }

    public Mono<Void> initialize() {
        return store.initialize(() -> client.fetch(ConfigMap.class, ProxySettings.CONFIG)
            .map(config -> readGroup(config.getData(), "basic", BasicSetting.class, BasicSetting.DEFAULT))
            .defaultIfEmpty(BasicSetting.DEFAULT)
            .map(NcmApiAccessSettings::fromLegacy));
    }

    public Mono<BasicSetting> current() {
        return store.current().map(NcmApiAccessSettings::toBasic);
    }

    public Mono<NcmApiAccessConfig> read() {
        return store.read().map(this::snapshot);
    }

    public Mono<NcmApiAccessConfig> save(NcmApiAccessConfig request) {
        return Mono.defer(() -> {
            if (request.revision() == null || request.settings() == null) {
                return Mono.error(new ServerWebInputException("缺少配置版本或接口开放设置"));
            }
            request.settings().validateForSave();
            return store.save(request.revision(), request.settings()).map(this::snapshot);
        });
    }

    private NcmApiAccessConfig snapshot(VersionedSettingsStore.Snapshot<NcmApiAccessSettings> saved) {
        return new NcmApiAccessConfig(saved.revision(), saved.settings());
    }
}

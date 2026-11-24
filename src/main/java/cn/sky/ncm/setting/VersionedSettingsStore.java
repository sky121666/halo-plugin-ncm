package cn.sky.ncm.setting;

import cn.sky.ncm.security.SecretStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;

/** 独立设置的存储、版本校验和运行时缓存。 */
final class VersionedSettingsStore<T> {

    static final String SETTINGS = "settings";
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Duration CACHE_TIME = Duration.ofSeconds(10);

    record Snapshot<T>(Long revision, T settings) {
    }

    private final ReactiveExtensionClient client;
    private final String name;
    private final String purpose;
    private final String label;
    private final Class<T> type;
    private volatile Mono<T> cached;

    VersionedSettingsStore(ReactiveExtensionClient client, String name, String purpose, String label, Class<T> type) {
        this.client = client;
        this.name = name;
        this.purpose = purpose;
        this.label = label;
        this.type = type;
        this.cached = cache();
    }

    Mono<Void> initialize(Supplier<Mono<T>> legacy) {
        return client.fetch(ConfigMap.class, name)
            .switchIfEmpty(Mono.defer(() -> legacy.get().flatMap(this::create)))
            .doOnNext(this::snapshot)
            .then();
    }

    Mono<T> current() {
        return Mono.defer(() -> cached);
    }

    Mono<Snapshot<T>> read() {
        return required().map(this::snapshot);
    }

    Mono<Snapshot<T>> save(Long revision, T settings) {
        return save(revision, settings, current -> Mono.empty());
    }

    Mono<Snapshot<T>> save(Long revision, T settings, Function<T, Mono<Void>> validate) {
        return required().flatMap(config -> {
            var current = snapshot(config);
            if (!Objects.equals(revision, current.revision())) {
                return Mono.error(conflict());
            }
            return validate.apply(current.settings()).then(Mono.defer(() -> {
                var data = new LinkedHashMap<>(config.getData());
                data.put(SETTINGS, JSON.writeValueAsString(settings));
                config.setData(data);
                return client.update(config).map(this::snapshot);
            }));
        }).onErrorMap(OptimisticLockingFailureException.class, error -> conflict())
            .doOnNext(saved -> cached = cache());
    }

    private Mono<ConfigMap> create(T settings) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setLabels(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME,
            SecretStore.LABEL_PURPOSE, purpose));
        var config = new ConfigMap();
        config.setMetadata(metadata);
        config.setData(Map.of(SETTINGS, JSON.writeValueAsString(settings)));
        return client.create(config)
            .onErrorResume(error -> client.fetch(ConfigMap.class, name).switchIfEmpty(Mono.error(error)));
    }

    private Mono<ConfigMap> required() {
        return client.fetch(ConfigMap.class, name)
            .switchIfEmpty(Mono.error(new IllegalStateException(label + "尚未初始化")));
    }

    private Mono<T> cache() {
        return Mono.defer(this::read).map(Snapshot::settings).cache(CACHE_TIME);
    }

    private Snapshot<T> snapshot(ConfigMap config) {
        requireOwnership(config);
        var settings = readGroup(config.getData(), SETTINGS, type, null);
        if (settings == null || config.getMetadata().getVersion() == null) {
            throw new IllegalStateException(label + "尚未初始化");
        }
        return new Snapshot<>(config.getMetadata().getVersion(), settings);
    }

    private void requireOwnership(ConfigMap config) {
        if (!SecretStore.ownedConfigMap(config)
            || !purpose.equals(config.getMetadata().getLabels().get(SecretStore.LABEL_PURPOSE))) {
            throw new IllegalStateException(label + "资源归属不匹配");
        }
    }

    static <T> T readGroup(Map<String, String> data, String group, Class<T> type, T defaults) {
        var json = data == null ? null : data.get(group);
        if (json == null || json.isBlank()) {
            return defaults;
        }
        try {
            T value = JSON.readValue(json, type);
            return value == null ? defaults : value;
        } catch (RuntimeException error) {
            throw new IllegalStateException("无法读取网抑云配置组 " + group);
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "设置已更新，请重新加载后保存");
    }
}

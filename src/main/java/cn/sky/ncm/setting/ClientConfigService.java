package cn.sky.ncm.setting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class ClientConfigService {

    public static final String FOLIA = "folia";
    private final ReactiveExtensionClient client;
    private final ClientConfigPolicy policies;

    public ClientConfigService(ReactiveExtensionClient client, ClientConfigPolicy policies) {
        this.client = client;
        this.policies = policies;
    }

    public Mono<Void> migrateFoliaDefaults(PlayerSetting legacy) {
        var scope = siteScope(FOLIA);
        var playback = Map.<String, Object>of("volume", legacy.volumePercent() / 100.0,
            "loopMode", switch (legacy.playMode()) {
                case "order" -> "off";
                case "single" -> "one";
                default -> "all";
            });
        return fetch(scope).switchIfEmpty(Mono.defer(() -> create(scope, Map.of("playback", playback))
            .onErrorResume(ResponseStatusException.class, error -> {
                if (!error.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                    return Mono.error(error);
                }
                return fetch(scope).switchIfEmpty(Mono.error(error));
            }))).then();
    }

    Mono<PlayerSetting> foliaPlayer(Boolean resume) {
        return site(FOLIA).map(saved -> {
            var playback = saved.config() != null && saved.config().get("playback") instanceof Map<?, ?> group
                ? group : Map.of();
            String mode = switch (Objects.toString(playback.get("loopMode"), "")) {
                case "off" -> "order";
                case "one" -> "single";
                default -> "loop";
            };
            int volume = playback.get("volume") instanceof Number n ? (int) Math.round(n.doubleValue() * 100) : 100;
            return new PlayerSetting(mode, volume, resume);
        });
    }

    public Mono<Snapshot> site(String clientId) {
        return Mono.defer(() -> read(siteScope(clientId)));
    }

    public Mono<Personal> personal(String clientId, String username) {
        return personalScope(clientId, username).flatMap(scope ->
            read(scope).map(snapshot -> new Personal(scope.ownerKey(), snapshot)));
    }

    public Mono<Snapshot> saveSite(String clientId, int schemaVersion, String revision, Map<String, Object> config) {
        return Mono.defer(() -> save(siteScope(clientId), schemaVersion, revision, config));
    }

    public Mono<Snapshot> restoreSite(String clientId, int schemaVersion, String revision) {
        return Mono.defer(() -> {
            var scope = siteScope(clientId);
            return fetch(scope).switchIfEmpty(Mono.error(noPreviousVersion())).flatMap(existing -> {
                if (!scope.revision(existing.getMetadata()).equals(revision)) {
                    return Mono.error(conflict());
                }
                var previous = existing.getSpec().previousConfig();
                if (previous == null) {
                    return Mono.error(noPreviousVersion());
                }
                scope.policy().validate(schemaVersion, previous, true);
                return update(scope, existing, previous);
            }).onErrorMap(OptimisticLockingFailureException.class, error -> conflict())
                .map(value -> snapshot(scope, value));
        });
    }

    public Mono<Snapshot> savePersonal(String clientId, String username, int schemaVersion, String revision,
                                       Map<String, Object> config) {
        return personalScope(clientId, username).flatMap(scope -> save(scope, schemaVersion, revision, config));
    }

    private Scope siteScope(String clientId) {
        return new Scope(clientId, null, policies.resolve(clientId));
    }

    private Mono<Scope> personalScope(String clientId, String username) {
        return Mono.defer(() -> {
            var policy = policies.resolve(clientId);
            return client.fetch(User.class, username)
                .filter(user -> user.getMetadata().getDeletionTimestamp() == null
                    && user.getMetadata().getCreationTimestamp() != null)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录 Halo")))
                .map(user -> new Scope(clientId, digest(username + '\n' + user.getMetadata().getCreationTimestamp()),
                    policy));
        });
    }

    private Mono<Snapshot> read(Scope scope) {
        return fetch(scope).map(value -> snapshot(scope, value))
            .switchIfEmpty(Mono.fromSupplier(() ->
                new Snapshot(scope.policy().schemaVersion(), scope.revision(null), null, false)));
    }

    private Mono<Snapshot> save(Scope scope, int schemaVersion, String revision, Map<String, Object> config) {
        scope.policy().validate(schemaVersion, config, scope.ownerKey() == null);
        return fetch(scope).flatMap(existing -> {
            if (!scope.revision(existing.getMetadata()).equals(revision)) {
                return Mono.error(conflict());
            }
            return update(scope, existing, config);
        }).switchIfEmpty(Mono.defer(() -> scope.revision(null).equals(revision)
            ? create(scope, config) : Mono.error(conflict())))
            .onErrorMap(OptimisticLockingFailureException.class, error -> conflict())
            .map(value -> snapshot(scope, value));
    }

    private Mono<NcmClientConfig> update(Scope scope, NcmClientConfig existing, Map<String, Object> config) {
        var current = existing.getSpec().config();
        if (scope.ownerKey() == null && current.equals(config)) {
            return Mono.just(existing);
        }
        existing.setSpec(scope.spec(config, scope.ownerKey() == null ? current : null));
        return client.update(existing);
    }

    private Mono<NcmClientConfig> fetch(Scope scope) {
        return client.fetch(NcmClientConfig.class, scope.name()).doOnNext(value -> {
            var spec = value.getSpec();
            if (spec == null || !scope.clientId().equals(spec.clientId())
                || !Objects.equals(scope.ownerKey(), spec.ownerKey())) {
                throw new IllegalStateException("配置资源归属无效");
            }
            scope.policy().validate(spec.schemaVersion(), spec.config(), scope.ownerKey() == null);
        });
    }

    private Mono<NcmClientConfig> create(Scope scope, Map<String, Object> config) {
        var resource = new NcmClientConfig();
        var metadata = new Metadata();
        metadata.setName(scope.name());
        resource.setMetadata(metadata);
        resource.setSpec(scope.spec(config, null));
        return client.create(resource).onErrorResume(error -> fetch(scope)
            .flatMap(existing -> Mono.<NcmClientConfig>error(conflict()))
            .switchIfEmpty(Mono.error(error)));
    }

    private static Snapshot snapshot(Scope scope, NcmClientConfig value) {
        return new Snapshot(value.getSpec().schemaVersion(), scope.revision(value.getMetadata()),
            value.getSpec().config(), scope.ownerKey() == null && value.getSpec().previousConfig() != null);
    }

    private static ResponseStatusException noPreviousVersion() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "暂无可恢复的站点默认");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "配置已更新，请重新读取");
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 24);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Schema(name = "NcmConfigSnapshot", requiredProperties = {"schemaVersion", "revision"})
    public record Snapshot(int schemaVersion, String revision, Map<String, Object> config, boolean hasPrevious) {
    }

    public record Personal(String profileKey, Snapshot snapshot) {
    }

    private record Scope(String clientId, String ownerKey, ClientConfigPolicy.Policy policy) {
        String name() {
            return ownerKey == null ? "site-" + digest(clientId) : "user-" + digest(clientId + '\n' + ownerKey);
        }

        // 新记录也有独立令牌。切换账号、设备或重建资源后，旧令牌不能覆盖新记录。
        String revision(MetadataOperator metadata) {
            String version = metadata == null ? "new" : metadata.getCreationTimestamp() + ":" + metadata.getVersion();
            return digest(name() + '\n' + version);
        }

        NcmClientConfig.Spec spec(Map<String, Object> config, Map<String, Object> previousConfig) {
            return new NcmClientConfig.Spec(clientId, ownerKey, policy.schemaVersion(), config, previousConfig);
        }
    }
}

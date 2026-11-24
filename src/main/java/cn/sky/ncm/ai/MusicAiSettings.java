package cn.sky.ncm.ai;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class MusicAiSettings {
    private static final Logger LOG = LoggerFactory.getLogger(MusicAiSettings.class);
    private static final String GROUP = "ai";
    private static final String ENABLED = "enabled";
    private static final String LEGACY_CONFIG = "plugin-ncm-ai-configmap";
    private static final String MIGRATED = "ncm.halo.run/ai-settings-migrated";
    private static final JsonMapper JSON = JsonMapper.shared();

    private final ReactiveExtensionClient client;
    private final String configName;
    private final RoleService roles;

    MusicAiSettings(ReactiveExtensionClient client, PluginContext context, RoleService roles) {
        this.client = client;
        this.configName = context.getConfigMapName();
        this.roles = roles;
    }

    enum Access { ALLOWED, DENIED, DISABLED }

    Mono<Access> access(Authentication viewer) {
        // Permission changes should take effect as soon as the Console saves this ConfigMap.
        return Mono.defer(() -> client.fetch(ConfigMap.class, configName))
            .map(MusicAiSettings::group)
            .defaultIfEmpty(JSON.createObjectNode())
            .flatMap(group -> access(group, viewer))
            .onErrorReturn(Access.DISABLED);
    }

    private Mono<Access> access(JsonNode group, Authentication viewer) {
        var enabled = group.path(ENABLED);
        if (!group.isObject() || (!enabled.isMissingNode() && (!enabled.isBoolean() || !enabled.asBoolean()))) {
            return Mono.just(Access.DISABLED);
        }
        var audience = group.path("audience");
        if (audience.isMissingNode() || "signed-in".equals(audience.asText())) {
            return Mono.just(Access.ALLOWED);
        }
        var allowedRoles = group.path("allowedRoles");
        if (!"roles".equals(audience.asText()) || !allowedRoles.isArray() || allowedRoles.isEmpty()) {
            return Mono.just(Access.DENIED);
        }

        var authorities = viewer.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        var matchingRoles = new HashSet<String>();
        for (var role : allowedRoles) {
            if (role.isTextual() && authorities.contains("ROLE_" + role.asText())) {
                matchingRoles.add(role.asText());
            }
        }
        if (matchingRoles.isEmpty()) {
            return Mono.just(Access.DENIED);
        }
        // Check current bindings as well as the request's authorities to respect removals and token scopes.
        return roles.getRolesByUsername(viewer.getName()).any(matchingRoles::contains)
            .map(matches -> matches ? Access.ALLOWED : Access.DENIED)
            .onErrorReturn(Access.DENIED);
    }

    public Mono<Void> migrate() {
        return Mono.defer(() -> client.fetch(ConfigMap.class, configName).flatMap(this::migrateInto))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
                .filter(OptimisticLockingFailureException.class::isInstance))
            .timeout(Duration.ofSeconds(5))
            .doOnError(error -> LOG.warn("音乐 AI 旧开关迁移失败，保留已有设置 ({})",
                error.getClass().getSimpleName()))
            .onErrorComplete()
            .then();
    }

    private Mono<ConfigMap> migrateInto(ConfigMap config) {
        var metadata = config.getMetadata();
        var annotations = new LinkedHashMap<>(Objects.requireNonNullElse(metadata.getAnnotations(), Map.of()));
        if ("true".equals(annotations.get(MIGRATED))) {
            return Mono.empty();
        }
        return client.fetch(ConfigMap.class, LEGACY_CONFIG)
            .map(MusicAiSettings::group)
            .filter(legacy -> legacy.path(ENABLED).isBoolean())
            .flatMap(legacy -> {
                var current = group(config);
                if (!current.has(ENABLED)) {
                    current.set(ENABLED, legacy.get(ENABLED));
                    var data = new LinkedHashMap<>(Objects.requireNonNullElse(config.getData(), Map.of()));
                    data.put(GROUP, current.toString());
                    config.setData(data);
                }
                annotations.put(MIGRATED, "true");
                metadata.setAnnotations(annotations);
                return client.update(config);
            });
    }

    private static ObjectNode group(ConfigMap config) {
        var stored = Objects.requireNonNullElse(config.getData(), Map.<String, String>of()).get(GROUP);
        if (stored == null) {
            return JSON.createObjectNode();
        }
        if (JSON.readTree(stored) instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("音乐 AI 配置格式无效");
    }
}

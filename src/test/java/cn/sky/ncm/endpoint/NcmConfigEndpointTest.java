package cn.sky.ncm.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.ClientConfigService;
import cn.sky.ncm.setting.ClientConfigPolicy;
import cn.sky.ncm.setting.NcmClientConfig;
import cn.sky.ncm.setting.PlayerSetting;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NcmConfigEndpointTest {

    private static final String ORIGIN = "https://music.test";
    private static final String MINE = "/my-configs/folia";
    private static final String SITE = "/config-defaults/folia";
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.shared();
    private final Map<String, NcmClientConfig> resources = new HashMap<>();
    private final Map<String, Instant> users = new HashMap<>(Map.of("owner", CREATED, "alice", CREATED,
        "bob", CREATED, "manager", CREATED, "operator", CREATED));
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final ClientConfigService service = new ClientConfigService(client, new ClientConfigPolicy());
    private WebTestClient web;

    @BeforeEach
    void configureEndpoint() {
        when(client.fetch(eq(User.class), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            return Mono.defer(() -> Mono.justOrEmpty(users.get(name)).map(created -> {
                var user = new User();
                var metadata = new Metadata();
                metadata.setName(name);
                metadata.setCreationTimestamp(created);
                user.setMetadata(metadata);
                return user;
            }));
        });
        when(client.fetch(eq(NcmClientConfig.class), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1);
            return Mono.defer(() -> Mono.justOrEmpty(resources.get(name))
                .map(value -> copy(value, value.getMetadata().getVersion())));
        });
        when(client.create(any(NcmClientConfig.class))).thenAnswer(invocation -> {
            NcmClientConfig value = invocation.getArgument(0);
            return Mono.fromSupplier(() -> {
                var created = copy(value, 0L);
                if (resources.putIfAbsent(value.getMetadata().getName(), created) != null) {
                    throw new DuplicateKeyException("资源已存在");
                }
                return created;
            });
        });
        when(client.update(any(NcmClientConfig.class))).thenAnswer(invocation -> {
            NcmClientConfig value = invocation.getArgument(0);
            return Mono.fromSupplier(() -> resources.compute(value.getMetadata().getName(), (name, existing) -> {
                var version = existing.getMetadata().getVersion();
                if (!version.equals(value.getMetadata().getVersion())) {
                    throw new OptimisticLockingFailureException("版本已改变");
                }
                return copy(value, version + 1);
            }));
        });
        service.migrateFoliaDefaults(new PlayerSetting("single", 35, false)).block();
        var roles = mock(RoleService.class);
        when(roles.contains(any(), eq(List.of("plugin-ncm-role-debug")))).thenAnswer(invocation ->
            Mono.just(invocation.<List<String>>getArgument(0).contains("plugin-ncm-role-debug")));
        web = WebTestClient.bindToRouterFunction(new NcmConfigEndpoint(service, new RateLimiter(), roles).endpoint())
            .webFilter((exchange, chain) -> {
                var name = exchange.getRequest().getHeaders().getFirst("X-Test-Actor");
                var response = chain.filter(exchange).onErrorResume(ResponseStatusException.class, error -> {
                    exchange.getResponse().setStatusCode(error.getStatusCode());
                    return exchange.getResponse().setComplete();
                });
                if (name == null) {
                    return response;
                }
                var role = switch (name) {
                    case "owner" -> "ROLE_super-role";
                    case "manager" -> "ROLE_role-template-manage-plugins";
                    case "operator" -> "ROLE_plugin-ncm-role-debug";
                    default -> "ROLE_authenticated";
                };
                var auth = UsernamePasswordAuthenticationToken.authenticated(name, "",
                    AuthorityUtils.createAuthorityList(role));
                return response.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
            })
            .configureClient().baseUrl(ORIGIN).build();
    }

    @Test
    void onlySuperAdminPublishesAndMigrationKeepsThePublishedValues() {
        var initial = read(SITE, null);
        assertThat(initial.path("config").path("playback").path("volume").asDouble()).isEqualTo(0.35);
        var request = input(initial, 0.64);
        for (String name : new String[]{"alice", "manager"}) {
            assertThat(read(MINE, name).path("canPublishSite").asBoolean()).isFalse();
            write(SITE, name, ORIGIN, request).expectStatus().isForbidden();
        }
        assertThat(read(MINE, "owner").path("canPublishSite").asBoolean()).isTrue();
        assertThat(read(MINE, "owner").path("canViewDiagnostics").asBoolean()).isTrue();
        assertThat(read(MINE, "operator").path("canViewDiagnostics").asBoolean()).isTrue();
        assertThat(read(MINE, "operator").path("canPublishSite").asBoolean()).isFalse();
        assertThat(read(MINE, "manager").path("canViewDiagnostics").asBoolean()).isFalse();
        write(SITE, "operator", ORIGIN, request).expectStatus().isForbidden();
        write(SITE, null, ORIGIN, request).expectStatus().isUnauthorized();
        String privateField = request.replace("\"volume\":0.64", "\"volume\":0.64,\"audioEqualizerSettings\":{}");
        write(SITE, "owner", ORIGIN, privateField).expectStatus().isBadRequest();
        write(SITE, "owner", ORIGIN, request).expectStatus().isOk().expectBody().jsonPath("$.config.playback.volume").isEqualTo(0.64);
        service.migrateFoliaDefaults(new PlayerSetting("loop", 100, true)).block();
        assertThat(read(SITE, null).path("config").path("playback").path("volume").asDouble()).isEqualTo(0.64);
        write(SITE, "owner", ORIGIN, request).expectStatus().isEqualTo(409);
    }

    @Test
    void privateConfigurationsAndVersionsBelongToTheCurrentHaloAccount() {
        web.get().uri(MINE).exchange().expectStatus().isUnauthorized();
        var mine = read(MINE, "alice").path("snapshot");
        var other = read(MINE, "bob").path("snapshot");
        assertThat(read(MINE, "alice").path("profileKey").asText())
            .isNotEqualTo(read(MINE, "bob").path("profileKey").asText());
        assertThat(other.path("revision").asText()).isNotEqualTo(mine.path("revision").asText());
        var request = input(mine, 0);
        write(MINE, "bob", ORIGIN, request).expectStatus().isEqualTo(409);
        write(MINE, "alice", ORIGIN, request).expectStatus().isOk()
            .expectHeader().valueMatches("Cache-Control", ".*no-store.*private.*")
            .expectBody().jsonPath("$.config.playback.volume").isEqualTo(0.0);
        var bob = read(MINE, "bob").path("snapshot").path("config");
        assertThat(bob.isNull() || bob.isMissingNode()).isTrue();
        assertThat(read(SITE, null).path("config").path("playback").path("volume").asDouble()).isEqualTo(0.35);
        users.put("alice", CREATED.plusSeconds(1));
        var recreated = read(MINE, "alice").path("snapshot");
        assertThat(recreated.path("config").isNull() || recreated.path("config").isMissingNode()).isTrue();
        write(MINE, "alice", ORIGIN, request).expectStatus().isEqualTo(409);
    }

    @Test
    void restoresOnlyThePreviousSiteDefaultWithTheCurrentRevision() {
        var initial = read(SITE, null);
        assertThat(initial.path("hasPrevious").asBoolean()).isFalse();
        restore("owner", initial).expectStatus().isEqualTo(409);
        write(SITE, "owner", ORIGIN, input(initial, 0.72)).expectStatus().isOk();
        var current = read(SITE, null);
        assertThat(current.path("hasPrevious").asBoolean()).isTrue();
        assertThat(current.has("previousConfig")).isFalse();
        restore("alice", current).expectStatus().isForbidden();
        restore("manager", current).expectStatus().isForbidden();
        restore(null, current).expectStatus().isUnauthorized();
        restore("owner", initial).expectStatus().isEqualTo(409);
        restore("owner", current).expectStatus().isOk()
            .expectBody().jsonPath("$.config.playback.volume").isEqualTo(0.35);
        assertThat(read(SITE, null).path("config").path("playback").path("volume").asDouble()).isEqualTo(0.35);
    }

    @Test
    void clearingMyConfigurationUsesTheSameVersionAndKeepsOtherOwners() {
        var mine = read(MINE, "alice").path("snapshot");
        var other = read(MINE, "bob").path("snapshot");
        write(MINE, "alice", ORIGIN, input(mine, 0.64)).expectStatus().isOk();
        write(MINE, "bob", ORIGIN, input(other, 0.27)).expectStatus().isOk();
        write(MINE, "alice", ORIGIN, clearInput(mine)).expectStatus().isEqualTo(409);
        write(MINE, "alice", ORIGIN, clearInput(read(MINE, "alice").path("snapshot")))
            .expectStatus().isOk().expectBody().jsonPath("$.config").isEmpty();

        assertThat(read(MINE, "bob").path("snapshot").path("config").path("playback").path("volume").asDouble())
            .isEqualTo(0.27);
        assertThat(read(SITE, null).path("config").path("playback").path("volume").asDouble()).isEqualTo(0.35);
    }

    @Test
    void validatesRequestOriginAndStrictBoundedJson() {
        var request = input(read(MINE, "alice").path("snapshot"), 0.25);
        write(MINE, "alice", "https://elsewhere.test", request).expectStatus().isForbidden();
        write(MINE, "alice", null, request).expectStatus().isForbidden();
        write(MINE, "alice", ORIGIN, request.replace("\"volume\":0.25", "\"volume\":1.01"))
            .expectStatus().isBadRequest();
        write(MINE, "alice", ORIGIN, request.replace("\"volume\":0.25", "\"volume\":\"0.25\""))
            .expectStatus().isBadRequest();
        write(MINE, "alice", ORIGIN, request.replace("\"volume\":0.25", "\"volume\":0.25,\"username\":\"bob\""))
            .expectStatus().isBadRequest();
        write(MINE, "alice", ORIGIN, request + " ".repeat(64 * 1024)).expectStatus().isEqualTo(413);
        web.get().uri("/config-defaults/missing-theme").exchange().expectStatus().isNotFound();
        write(MINE, "alice", ORIGIN, request).expectStatus().isOk();
    }

    private JsonNode read(String path, String actor) {
        var request = web.get().uri(path);
        if (actor != null) {
            request.header("X-Test-Actor", actor);
        }
        byte[] body = request.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return JSON.readTree(body);
    }

    private WebTestClient.ResponseSpec write(String path, String actor, String origin, String input) {
        return mutate(HttpMethod.PUT, path, actor, origin, input);
    }

    private WebTestClient.ResponseSpec restore(String actor, JsonNode snapshot) {
        return mutate(HttpMethod.POST, SITE + "/restore", actor, ORIGIN, JSON.writeValueAsString(
            Map.of("schemaVersion", 1, "revision", snapshot.path("revision").asText())));
    }

    private static String clearInput(JsonNode snapshot) {
        return JSON.writeValueAsString(Map.of("schemaVersion", 1, "revision", snapshot.path("revision").asText(),
            "config", Map.of()));
    }

    private WebTestClient.ResponseSpec mutate(HttpMethod method, String path, String actor, String origin, String input) {
        var request = web.method(method).uri(path).header("X-NCM-Request", "1").contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            request.header("X-Test-Actor", actor);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return request.bodyValue(input).exchange();
    }

    private static String input(JsonNode snapshot, double volume) {
        return JSON.writeValueAsString(Map.of("schemaVersion", 1, "revision", snapshot.path("revision").asText(),
            "config", Map.of("playback", Map.of("loopMode", "off", "volume", volume),
                "appearance", Map.of("mode", "default"), "visual", Map.of("showSubtitleTranslation", true))));
    }

    private static NcmClientConfig copy(NcmClientConfig value, Long version) {
        var duplicate = new NcmClientConfig();
        var metadata = new Metadata();
        metadata.setName(value.getMetadata().getName());
        metadata.setVersion(version);
        metadata.setCreationTimestamp(CREATED);
        duplicate.setMetadata(metadata);
        duplicate.setSpec(value.getSpec());
        return duplicate;
    }
}

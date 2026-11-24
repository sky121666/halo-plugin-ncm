package cn.sky.ncm.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;

class MusicAiSettingsTest {
    private static final String MAIN = "plugin-ncm-configmap";
    private static final String LEGACY = "plugin-ncm-ai-configmap";
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final RoleService roles = mock(RoleService.class);
    private final MusicAiSettings settings = new MusicAiSettings(client,
        PluginContext.builder().name("plugin-ncm").configMapName(MAIN).build(), roles);
    private final UsernamePasswordAuthenticationToken viewer = UsernamePasswordAuthenticationToken.authenticated(
        "reader", "unused", List.of(new SimpleGrantedAuthority("ROLE_guest")));

    @Test
    void migratesOnlyTheOldSwitchAndSkipsResetsAfterwards() {
        var main = config(MAIN, Map.of("advanced", "{\"cache\":600}", "ai", "{}"));
        var old = config(LEGACY, Map.of("ai", "{\"enabled\":false}"));
        when(client.fetch(ConfigMap.class, MAIN)).thenReturn(Mono.just(main));
        when(client.fetch(ConfigMap.class, LEGACY)).thenReturn(Mono.just(old));
        when(client.update(main)).thenReturn(Mono.just(main));

        StepVerifier.create(settings.migrate()).verifyComplete();
        assertThat(main.getData()).containsExactlyInAnyOrderEntriesOf(
            Map.of("advanced", "{\"cache\":600}", "ai", "{\"enabled\":false}"));
        assertThat(old.getData()).containsEntry("ai", "{\"enabled\":false}");
        assertThat(main.getMetadata().getAnnotations()).containsEntry("test.example/keep", "yes");

        main.setData(Map.of("ai", "{}"));
        StepVerifier.create(settings.migrate()).verifyComplete();
        assertThat(main.getData()).containsEntry("ai", "{}");
        verify(client).update(main);
    }

    @Test
    void keepsAdminEditIfConfigChangesDuringMigration() {
        var before = config(MAIN, Map.of("ai", "{}"));
        var adminEdit = config(MAIN, Map.of("ai", "{\"enabled\":false}", "advanced", "{\"cache\":120}"));
        var old = config(LEGACY, Map.of("ai", "{\"enabled\":true}"));
        when(client.fetch(ConfigMap.class, MAIN)).thenReturn(Mono.just(before)).thenReturn(Mono.just(adminEdit));
        when(client.fetch(ConfigMap.class, LEGACY)).thenReturn(Mono.just(old));
        when(client.update(before)).thenReturn(Mono.error(new OptimisticLockingFailureException("edited")));
        when(client.update(adminEdit)).thenReturn(Mono.just(adminEdit));

        StepVerifier.create(settings.migrate()).verifyComplete();
        assertThat(adminEdit.getData()).containsExactlyInAnyOrderEntriesOf(
            Map.of("ai", "{\"enabled\":false}", "advanced", "{\"cache\":120}"));
        verify(client).update(adminEdit);
    }

    @Test
    void freshInstallDefaultsToSignedInWithoutWritingConfiguration() {
        var main = config(MAIN, Map.of("ai", "{}"));
        when(client.fetch(ConfigMap.class, MAIN)).thenReturn(Mono.just(main));
        when(client.fetch(ConfigMap.class, LEGACY)).thenReturn(Mono.empty());

        StepVerifier.create(settings.migrate()).verifyComplete();
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.ALLOWED).verifyComplete();
        verify(client, never()).update(main);
        verifyNoInteractions(roles);
    }

    @Test
    void disabledOrUnreadableConfigurationRemainsClosed() {
        when(client.fetch(ConfigMap.class, MAIN)).thenReturn(
            Mono.just(config(MAIN, Map.of("ai", "{\"enabled\":false}"))),
            Mono.error(new IllegalStateException("setting unavailable")));

        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DISABLED).verifyComplete();
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DISABLED).verifyComplete();
        verifyNoInteractions(roles);
    }

    @Test
    void acceptsAnySelectedRoleAndAppliesRemovedAssignments() {
        setAi("{\"audience\":\"roles\",\"allowedRoles\":[\"editor\",\"guest\"]}");
        when(roles.getRolesByUsername("reader")).thenReturn(Flux.just("guest"), Flux.just("editor"));

        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.ALLOWED).verifyComplete();
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();
    }

    @Test
    void restrictsEmptyRoleListsAndDoesNotImplicitlyAuthorizeAdministrators() {
        setAi("{\"audience\":\"roles\",\"allowedRoles\":[]}");
        StepVerifier.create(settings.access(viewer)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();

        setAi("{\"audience\":\"roles\",\"allowedRoles\":[\"guest\"]}");
        var administrator = UsernamePasswordAuthenticationToken.authenticated("admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_super-role")));
        StepVerifier.create(settings.access(administrator)).expectNext(MusicAiSettings.Access.DENIED).verifyComplete();
        verifyNoInteractions(roles);
    }

    private void setAi(String json) {
        when(client.fetch(ConfigMap.class, MAIN)).thenReturn(Mono.just(config(MAIN, Map.of("ai", json))));
    }

    private static ConfigMap config(String name, Map<String, String> data) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setAnnotations(Map.of("test.example/keep", "yes"));
        var config = new ConfigMap();
        config.setMetadata(metadata);
        config.setData(data);
        return config;
    }
}

package cn.sky.ncm.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.featured.FeaturedService;
import cn.sky.ncm.security.SecretStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;

class ToolSettingsServiceTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final FeaturedService featured = mock(FeaturedService.class);
    private final FrontstageSettingsService frontstage = mock(FrontstageSettingsService.class);
    private final ToolSettingsService service = new ToolSettingsService(client, featured, frontstage);

    @Test
    void keepsExistingAiPolicyAndLeavesNativeNetworkSettingsIntact() {
        var advanced = "{\"rateLimitPublic\":40}";
        var ai = "{\"enabled\":false,\"audience\":\"roles\",\"allowedRoles\":[\"guest\"]}";
        var nativeConfig = config(ProxySettings.CONFIG, Map.of("ai", ai, "advanced", advanced));
        when(client.fetch(ConfigMap.class, ProxySettings.CONFIG)).thenReturn(Mono.just(nativeConfig));

        var result = initialize();
        assertThat(result.ai()).isEqualTo(new NcmToolSettings.Ai(false, "roles", List.of("guest")));
        assertThat(result.miniPlayer()).isEqualTo(NcmToolSettings.MiniPlayer.DEFAULT);
        assertThat(nativeConfig.getData()).containsExactlyInAnyOrderEntriesOf(Map.of("ai", ai, "advanced", advanced));
        verify(client, never()).update(nativeConfig);
    }

    @Test
    void restoresOldAiSwitchWhenNativeConfigHasNoExplicitValue() {
        when(client.fetch(ConfigMap.class, ProxySettings.CONFIG))
            .thenReturn(Mono.just(config(ProxySettings.CONFIG, Map.of("ai", "{}"))));
        when(client.fetch(ConfigMap.class, ToolSettingsService.LEGACY_AI))
            .thenReturn(Mono.just(config(ToolSettingsService.LEGACY_AI, Map.of("ai", "{\"enabled\":false}"))));

        assertThat(initialize().ai().enabled()).isFalse();
    }

    @Test
    void refusesToEnablePlayerWhileMusicPageIsClosed() {
        var config = savedConfig(new NcmToolSettings(NcmToolSettings.Ai.DEFAULT, NcmToolSettings.MiniPlayer.DEFAULT));
        when(client.fetch(ConfigMap.class, ToolSettingsService.CONFIG)).thenReturn(Mono.just(config));
        var page = mock(NcmFrontstageSettings.class);
        when(page.enabled()).thenReturn(false);
        when(frontstage.current()).thenReturn(Mono.just(page));

        var requested = new NcmToolSettings(NcmToolSettings.Ai.DEFAULT, new NcmToolSettings.MiniPlayer(true, "123"));
        StepVerifier.create(service.save(new NcmToolConfig(1L, requested, List.of())))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ServerWebInputException.class)
                .hasMessageContaining("请先开启音乐页"))
            .verify();
        verify(featured, never()).publishedPlaylist(any());
        verify(client, never()).update(config);
    }

    @Test
    void savesAiIndependentlyAfterTheSelectedPlaylistBecomesUnavailable() {
        var player = new NcmToolSettings.MiniPlayer(true, "123");
        var config = savedConfig(new NcmToolSettings(NcmToolSettings.Ai.DEFAULT, player));
        when(client.fetch(ConfigMap.class, ToolSettingsService.CONFIG)).thenReturn(Mono.just(config));
        when(client.update(config)).thenReturn(Mono.just(config));
        when(featured.publishedPlaylists()).thenReturn(Flux.empty());

        var requested = new NcmToolSettings(NcmToolSettings.Ai.CLOSED, player);
        StepVerifier.create(service.save(new NcmToolConfig(1L, requested, List.of())))
            .assertNext(result -> assertThat(result.settings()).isEqualTo(requested))
            .verifyComplete();
        verify(frontstage, never()).current();
        verify(featured, never()).publishedPlaylist(any());
    }

    private static ConfigMap savedConfig(NcmToolSettings settings) {
        var result = config(ToolSettingsService.CONFIG,
            Map.of(VersionedSettingsStore.SETTINGS, JsonMapper.shared().writeValueAsString(settings)));
        result.getMetadata().setVersion(1L);
        result.getMetadata().setLabels(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME,
            SecretStore.LABEL_PURPOSE, "tool-settings"));
        return result;
    }

    private NcmToolSettings initialize() {
        when(client.fetch(ConfigMap.class, ToolSettingsService.CONFIG)).thenReturn(Mono.empty());
        when(client.create(any(ConfigMap.class))).thenAnswer(invocation -> {
            ConfigMap saved = invocation.getArgument(0);
            saved.getMetadata().setVersion(1L);
            return Mono.just(saved);
        });
        StepVerifier.create(service.initialize()).verifyComplete();
        var stored = ArgumentCaptor.forClass(ConfigMap.class);
        verify(client).create(stored.capture());
        return JsonMapper.shared().readValue(stored.getValue().getData().get("settings"), NcmToolSettings.class);
    }

    private static ConfigMap config(String name, Map<String, String> data) {
        var metadata = new Metadata();
        metadata.setName(name);
        var config = new ConfigMap();
        config.setMetadata(metadata);
        config.setData(data);
        return config;
    }
}

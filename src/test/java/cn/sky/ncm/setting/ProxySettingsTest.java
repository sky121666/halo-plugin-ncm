package cn.sky.ncm.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.security.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

class ProxySettingsTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private final ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final SecretStore secrets = mock(SecretStore.class);
    private final ProxySettings service = new ProxySettings(fetcher, client, secrets);

    @Test
    void directModeDoesNotReadSecrets() {
        configure("", "");
        assertTrue(service.current().block(WAIT).isEmpty());
        verifyNoInteractions(client, secrets);
    }

    @Test
    void resolvesCredentialsFromTheSelectedSecret() {
        configure("http://127.0.0.1:7890", "proxy-fixture");
        var secret = new Secret();
        secret.setStringData(Map.of("username", "reader", "password", "fixture-pass"));
        when(client.fetch(Secret.class, "proxy-fixture")).thenReturn(Mono.just(secret));

        var configuration = service.current().block(WAIT).orElseThrow();
        assertEquals("reader", configuration.username());
        assertEquals("fixture-pass", configuration.password());
        assertFalse(configuration.toString().contains("fixture-pass"));
    }

    @Test
    void missingCredentialsFailClosed() {
        configure("http://127.0.0.1:7890", "proxy-fixture");
        when(client.fetch(Secret.class, "proxy-fixture")).thenReturn(Mono.empty());
        StepVerifier.create(service.current())
            .expectErrorSatisfies(error -> assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                ((ResponseStatusException) error).getStatusCode()))
            .verify(WAIT);
        assertEquals("配置无效", service.status());
    }

    @Test
    void migratesLegacyUrlWithoutOverwritingOtherProperties() throws Exception {
        String legacy = "http://reader:" + "fixture-pass" + "@127.0.0.1:7890";
        configure(legacy, "");
        var config = new ConfigMap();
        config.setData(Map.of("advanced", "{\"proxy\":\"" + legacy + "\",\"proxyHops\":2}", "other", "{}"));
        when(client.fetch(ConfigMap.class, ProxySettings.CONFIG)).thenReturn(Mono.just(config));
        when(client.update(any(ConfigMap.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(secrets.write(anyString(), eq("proxy-credentials"), anyString(), anyMap())).thenReturn(Mono.empty());
        var secret = new Secret();
        var metadata = new Metadata();
        metadata.setLabels(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME));
        secret.setMetadata(metadata);
        when(client.fetch(eq(Secret.class), anyString())).thenReturn(Mono.just(secret));
        when(secrets.read(anyString())).thenReturn(Mono.just(Map.of("username", "reader", "password", "fixture-pass")));

        var configuration = service.current().block(WAIT).orElseThrow();
        assertEquals("fixture-pass", configuration.password());
        var saved = new ObjectMapper().readTree(config.getData().get("advanced"));
        assertEquals("http://127.0.0.1:7890", saved.path("proxy").asText());
        assertEquals(2, saved.path("proxyHops").asInt());
        assertEquals("{}", config.getData().get("other"));
        String reference = saved.path("proxySecret").asText();
        assertTrue(reference.startsWith("plugin-ncm-proxy-"));
        verify(secrets).write(reference, "proxy-credentials", "网易云出站代理",
            Map.of("username", "reader", "password", "fixture-pass"));
    }

    private void configure(String address, String reference) {
        when(fetcher.fetch("advanced", AdvancedSetting.class)).thenReturn(Mono.just(
            new AdvancedSetting(address, true, 120, 120, 100, "", 1, reference, null, null, null)));
    }
}

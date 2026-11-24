package cn.sky.ncm.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

class SecretStoreTest {

    static SecretBox box() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 3);
        return new SecretBox(k, "test");
    }

    @Test
    void createsOwnedEncryptedSecretAndReadsItBack() {
        var client = mock(ReactiveExtensionClient.class);
        var store = new SecretStore(client, box());
        when(client.fetch(Secret.class, "ncm-account-site")).thenReturn(Mono.empty());
        when(client.create(any(Secret.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(store.write("ncm-account-site", "account", "desc",
            Map.of("cookie", "MUSIC_U=abc; __csrf=x"))).verifyComplete();

        var cap = ArgumentCaptor.forClass(Secret.class);
        verify(client).create(cap.capture());
        Secret s = cap.getValue();
        assertEquals(SecretStore.PLUGIN_NAME, s.getMetadata().getLabels().get(SecretStore.LABEL_PLUGIN));
        assertEquals("desc", s.getMetadata().getAnnotations().get(SecretStore.ANNOTATION_DESCRIPTION));
        assertEquals(Secret.SECRET_TYPE_OPAQUE, s.getType());
        assertTrue(SecretBox.isEncrypted(s.getStringData().get("cookie")), "Secret 里的值必须是密文");

        when(client.fetch(Secret.class, "ncm-account-site")).thenReturn(Mono.just(s));
        StepVerifier.create(store.read("ncm-account-site", "account"))
            .assertNext(m -> assertEquals("MUSIC_U=abc; __csrf=x", m.get("cookie")))
            .verifyComplete();
    }

    @Test
    void ignoresAndRefusesToOverwriteForeignSecret() {
        var client = mock(ReactiveExtensionClient.class);
        var store = new SecretStore(client, box());
        var foreign = new Secret();
        var m = new Metadata();
        m.setName("ncm-account-site");
        m.setLabels(new HashMap<>(Map.of(SecretStore.LABEL_PLUGIN, "other-plugin")));
        foreign.setMetadata(m);
        foreign.setStringData(Map.of("cookie", "secret-of-someone-else"));
        when(client.fetch(Secret.class, "ncm-account-site")).thenReturn(Mono.just(foreign));

        StepVerifier.create(store.read("ncm-account-site", "account")).verifyComplete();
        StepVerifier.create(store.write("ncm-account-site", "account", "desc", Map.of("cookie", "x")))
            .expectError(IllegalStateException.class)
            .verify();
        verify(client, never()).update(any(Secret.class));
    }

    @Test
    void wrongKeyIsReportedAsSecretException() {
        var client = mock(ReactiveExtensionClient.class);
        var s = new Secret();
        var m = new Metadata();
        m.setName("ncm-device-default");
        m.setLabels(new HashMap<>(Map.of(SecretStore.LABEL_PLUGIN, SecretStore.PLUGIN_NAME, SecretStore.LABEL_PURPOSE, "device")));
        s.setMetadata(m);
        s.setStringData(Map.of("anonymousToken", box().encrypt("token")));
        when(client.fetch(Secret.class, "ncm-device-default")).thenReturn(Mono.just(s));

        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 9);
        StepVerifier.create(new SecretStore(client, new SecretBox(other, "test")).read("ncm-device-default", "device"))
            .expectError(SecretBox.SecretException.class)
            .verify();
    }
}

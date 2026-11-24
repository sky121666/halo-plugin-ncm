package cn.sky.ncm.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

class AccountServiceTest {
    private static final Instant BORN = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant BOUND = BORN.plusSeconds(10);
    private static final String NAME = "user-alice";

    static SecretBox box() throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 5);
        Constructor<SecretBox> constructor = SecretBox.class.getDeclaredConstructor(byte[].class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(key, "test");
    }

    @Test
    @SuppressWarnings("deprecation")
    void verifiedLegacyBindingMigratesWithoutExposingCredentials() throws Exception {
        var state = new Store();
        var account = state.legacy();
        account.getSpec().setCookie("MUSIC_U=legacy; __csrf=c");
        StepVerifier.create(state.service.binding("alice"))
            .assertNext(binding -> assertEquals("legacy", binding.credentials().orElseThrow().cookie().get("MUSIC_U")))
            .verifyComplete();
        var migrated = state.account.get();
        assertNull(migrated.getSpec().getCookie());
        assertEquals(BORN.toString(), migrated.getSpec().getOwnerCreatedAt());
        var material = state.secrets.get(migrated.getSpec().getSecretName());
        assertTrue(SecretBox.isEncrypted(material.getStringData().get("cookie")));
    }

    @Test
    void recreatedHaloUserCannotReadTheOldBinding() throws Exception {
        var state = new Store();
        state.legacy();
        state.owner.get().setCreationTimestamp(BOUND.plusSeconds(1));
        StepVerifier.create(state.service.binding("alice")).verifyComplete();
    }

    @Test
    void rebindPublishesOwnerProfileAndNewSecretInOneRecord() throws Exception {
        var state = new Store();
        state.legacy();
        var actor = state.service.requireOwner("alice").block();
        var first = state.service.begin(NAME, actor).block();
        var competing = state.service.begin(NAME, actor).block();
        var saved = state.service.save(first, cookies("new"), request -> Mono.just(profile(2))).block();
        assertEquals(2L, saved.getStatus().getUserId());
        assertEquals("alice", saved.getSpec().getOwner());
        assertEquals(1, state.secrets.size());
        assertFalse(saved.getSpec().getSecretName().equals(AccountService.secretNameOf(NAME)));
        StepVerifier.create(state.service.save(competing, cookies("other"), request -> Mono.just(profile(3))))
            .expectErrorSatisfies(error -> assertEquals(409, ((ResponseStatusException) error).getStatusCode().value()))
            .verify();
        assertEquals(1, state.secrets.size());
        StepVerifier.create(state.service.binding("alice"))
            .assertNext(binding -> {
                assertEquals(2L, binding.account().getStatus().getUserId());
                assertEquals("new", binding.credentials().orElseThrow().cookie().get("MUSIC_U"));
            })
            .verifyComplete();
    }

    @Test
    void unlinkKeepsARevocationVersionAndRejectsTheOldBindingAttempt() throws Exception {
        var state = new Store();
        var actor = state.service.requireOwner("alice").block();
        var attempt = state.service.begin(NAME, actor).block();
        StepVerifier.create(state.service.revoke(NAME, "alice")).verifyComplete();
        assertTrue(state.account.get().getSpec().isRevoked());
        StepVerifier.create(state.service.save(attempt, cookies("expired"), request -> Mono.just(profile(2))))
            .expectErrorSatisfies(error -> assertEquals(409, ((ResponseStatusException) error).getStatusCode().value()))
            .verify();
        assertTrue(state.secrets.isEmpty());
        StepVerifier.create(state.service.binding("alice")).verifyComplete();
    }

    @Test
    void invalidUpstreamSessionLeavesTheExistingBindingIntact() throws Exception {
        var state = new Store();
        var existing = state.legacy();
        var attempt = state.service.begin(NAME, state.service.requireOwner("alice").block()).block();
        StepVerifier.create(state.service.save(attempt, cookies("blank-profile"), request -> Mono.just(profile(0))))
            .expectErrorSatisfies(error -> assertEquals(502, ((ResponseStatusException) error).getStatusCode().value()))
            .verify();
        assertEquals(existing, state.account.get());
        assertTrue(state.secrets.isEmpty());
    }

    private static List<String> cookies(String value) {
        return List.of("MUSIC_U=" + value + "; Path=/");
    }

    private static NcmResponse profile(long id) {
        var body = new ObjectMapper().createObjectNode();
        body.putObject("data").putObject("profile").put("userId", id);
        return NcmResponse.ok(body, List.of());
    }

    private static final class Store {
        final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        final AtomicReference<NcmAccount> account = new AtomicReference<>();
        final AtomicReference<Metadata> owner = new AtomicReference<>();
        final Map<String, Secret> secrets = new ConcurrentHashMap<>();
        final AccountService service;

        Store() throws Exception {
            var user = new User();
            var metadata = new Metadata();
            metadata.setName("alice");
            metadata.setCreationTimestamp(BORN);
            user.setMetadata(metadata);
            owner.set(metadata);
            var box = box();
            service = new AccountService(client, box, new SecretStore(client, box));
            when(client.fetch(User.class, "alice")).thenReturn(Mono.just(user));
            when(client.fetch(eq(NcmAccount.class), anyString())).thenAnswer(invocation ->
                Mono.justOrEmpty(NAME.equals(invocation.getArgument(1)) ? account.get() : null));
            when(client.fetch(eq(Secret.class), anyString())).thenAnswer(invocation ->
                Mono.justOrEmpty(secrets.get(invocation.getArgument(1))));
            when(client.create(any(Secret.class))).thenAnswer(invocation -> {
                Secret secret = invocation.getArgument(0);
                secrets.put(secret.getMetadata().getName(), secret);
                return Mono.just(secret);
            });
            when(client.delete(any(Secret.class))).thenAnswer(invocation -> {
                Secret secret = invocation.getArgument(0);
                return Mono.just(secrets.remove(secret.getMetadata().getName()));
            });
            when(client.create(any(NcmAccount.class))).thenAnswer(invocation -> {
                NcmAccount entry = invocation.getArgument(0);
                entry.getMetadata().setVersion(1L);
                entry.getMetadata().setCreationTimestamp(BOUND);
                return account.compareAndSet(null, entry) ? Mono.just(entry) : Mono.error(new IllegalStateException());
            });
            when(client.update(any(NcmAccount.class))).thenAnswer(invocation -> {
                NcmAccount entry = invocation.getArgument(0);
                NcmAccount previous = account.get();
                if (previous == null || !Objects.equals(entry.getMetadata().getVersion(), previous.getMetadata().getVersion())) {
                    return Mono.error(new OptimisticLockingFailureException("fixture version"));
                }
                entry.getMetadata().setVersion(previous.getMetadata().getVersion() + 1);
                return account.compareAndSet(previous, entry) ? Mono.just(entry)
                    : Mono.error(new OptimisticLockingFailureException("fixture version"));
            });
        }

        NcmAccount legacy() {
            var entry = new NcmAccount();
            var metadata = new Metadata();
            metadata.setName(NAME);
            metadata.setCreationTimestamp(BOUND);
            metadata.setVersion(1L);
            entry.setMetadata(metadata);
            var spec = new NcmAccount.Spec();
            spec.setOwner("alice");
            entry.setSpec(spec);
            entry.setStatus(new NcmAccount.Status());
            account.set(entry);
            return entry;
        }
    }
}

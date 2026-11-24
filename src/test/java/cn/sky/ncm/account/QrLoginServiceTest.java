package cn.sky.ncm.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import cn.sky.ncm.api.CustomApis;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.security.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;

class QrLoginServiceTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private static final List<String> COOKIES = List.of("MUSIC_U=fixture-session; Path=/");

    private final CustomApis upstream = mock(CustomApis.class);
    private final AccountService accounts = mock(AccountService.class);
    private final Clock clock = mock(Clock.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T00:00:00Z"));
    private final QrLoginService service = new QrLoginService(upstream, accounts, new RateLimiter(), clock);

    private final AccountService.Owner alice = new AccountService.Owner("alice", Instant.EPOCH);
    private final AccountService.BindingChange change =
        new AccountService.BindingChange("user-alice", alice, Optional.empty());

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(upstream.loginQrKey(any())).thenReturn(Mono.just(answer(
            "{\"data\":{\"unikey\":\"upstream-fixture\"}}", List.of())));
        when(upstream.loginQrCreate(any())).thenReturn(answer(
            "{\"data\":{\"qrurl\":\"https://music.163.com/login?codekey=upstream-fixture\"}}", List.of()));
        when(upstream.loginQrCheck(eq("upstream-fixture"), any())).thenReturn(Mono.just(answer(
            "{\"code\":803}", COOKIES)));
        when(accounts.requireOwner("alice")).thenReturn(Mono.just(alice));
        when(accounts.requireOwner("bob")).thenReturn(Mono.just(new AccountService.Owner("bob", Instant.EPOCH)));
        when(accounts.begin("user-alice", alice)).thenReturn(Mono.just(change));
    }

    @Test
    void authorizedPollingDoesNotSaveAndConfirmConsumesTheAttempt() {
        String key = start("user-alice", "alice");
        assertNotEquals("upstream-fixture", key);
        rejected(service.confirm(key, "user-alice", "alice"), HttpStatus.CONFLICT);

        var status = service.check(key, "user-alice", "alice").block(WAIT);
        assertEquals(803, status.path("code").asInt());
        assertTrue(status.path("account").isMissingNode());
        assertTrue(status.path("cookie").isMissingNode());
        assertEquals(803, service.check(key, "user-alice", "alice").block(WAIT).path("code").asInt());
        verify(upstream, times(1)).loginQrCheck(eq("upstream-fixture"), any());
        verify(accounts, never()).save(any(), any(), any());

        when(accounts.save(eq(change), eq(COOKIES), any())).thenReturn(Mono.just(account()));
        assertEquals(200, service.confirm(key, "user-alice", "alice").block(WAIT).path("code").asInt());
        rejected(service.confirm(key, "user-alice", "alice"), HttpStatus.GONE);
        verify(accounts, times(1)).save(eq(change), eq(COOKIES), any());
    }

    @Test
    void expiredAttemptAndOtherAccountScopesCannotConfirm() {
        String key = start("user-alice", "alice");
        service.check(key, "user-alice", "alice").block(WAIT);
        rejected(service.confirm(key, "user-alice", "bob"), HttpStatus.GONE);
        rejected(service.confirm(key, "site", "alice"), HttpStatus.GONE);
        now.updateAndGet(time -> time.plus(QrLoginService.TTL));
        assertEquals(800, service.check(key, "user-alice", "alice").block(WAIT).path("code").asInt());
        rejected(service.confirm(key, "user-alice", "alice"), HttpStatus.GONE);
        verify(accounts, never()).save(any(), any(), any());
    }

    @Test
    void onlyOneConfirmationSavesWhileTheFirstWriteIsInProgress() {
        String key = start("user-alice", "alice");
        service.check(key, "user-alice", "alice").block(WAIT);
        Sinks.One<NcmAccount> pending = Sinks.one();
        when(accounts.save(eq(change), eq(COOKIES), any())).thenReturn(pending.asMono());

        StepVerifier.create(service.confirm(key, "user-alice", "alice"))
            .then(() -> rejected(service.confirm(key, "user-alice", "alice"), HttpStatus.CONFLICT))
            .then(() -> assertEquals(Sinks.EmitResult.OK, pending.tryEmitValue(account())))
            .assertNext(result -> assertEquals(200, result.path("code").asInt()))
            .verifyComplete();
        verify(accounts, times(1)).save(eq(change), eq(COOKIES), any());
    }

    private String start(String account, String user) {
        return service.createQr(account, user).block(WAIT).path("key").asText();
    }

    private static void rejected(Mono<?> call, HttpStatus status) {
        StepVerifier.create(call)
            .expectErrorSatisfies(error -> assertEquals(status, ((ResponseStatusException) error).getStatusCode()))
            .verify(WAIT);
    }

    private static NcmResponse answer(String json, List<String> cookies) throws Exception {
        return new NcmResponse(200, new ObjectMapper().readTree(json), cookies);
    }

    private static NcmAccount account() {
        var account = new NcmAccount();
        var metadata = new Metadata();
        metadata.setName("user-alice");
        account.setMetadata(metadata);
        return account;
    }
}

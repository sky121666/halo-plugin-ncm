package cn.sky.ncm.endpoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.NcmAccount;
import cn.sky.ncm.account.PhoneLoginService;
import cn.sky.ncm.account.QrLoginService;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

class NcmUcBindingTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final SecretStore secrets = mock(SecretStore.class);
    private final AccountService accounts = new AccountService(client, mock(SecretBox.class), secrets);
    private final NcmUcEndpoint endpoint = new NcmUcEndpoint(accounts, mock(QrLoginService.class),
        mock(PhoneLoginService.class));

    @Test
    void showsConsoleBindingToItsOwner() {
        givenAccount("site", "owner", 101L);
        givenNoPersonalAccount("owner");

        bindingAs("owner").jsonPath("$.bound").isEqualTo(true)
            .jsonPath("$.userId").isEqualTo(101)
            .jsonPath("$.readable").isEqualTo(true)
            .jsonPath("$.managedInConsole").isEqualTo(true)
            .jsonPath("$.cookie").doesNotExist();
    }

    @Test
    void keepsConsoleBindingPrivateToItsOwner() {
        givenAccount("site", "owner", 101L);
        givenNoPersonalAccount("reader");

        bindingAs("reader").jsonPath("$.bound").isEqualTo(false)
            .jsonPath("$.userId").doesNotExist()
            .jsonPath("$.managedInConsole").doesNotExist();
        verifyNoInteractions(secrets);
    }

    @Test
    void prefersAnExistingPersonalBinding() {
        givenAccount("site", "owner", 101L);
        givenAccount(AccountService.userAccountName("owner"), "owner", 202L);

        bindingAs("owner").jsonPath("$.bound").isEqualTo(true)
            .jsonPath("$.userId").isEqualTo(202)
            .jsonPath("$.readable").isEqualTo(true)
            .jsonPath("$.managedInConsole").doesNotExist();
    }

    @Test
    void rejectsAnonymousBindingStatus() {
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build()
            .get().uri("/binding").exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.BodyContentSpec bindingAs(String username) {
        var auth = new UsernamePasswordAuthenticationToken(username, null, List.of());
        var router = endpoint.endpoint().filter((request, next) -> next.handle(request)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)));
        return WebTestClient.bindToRouterFunction(router).build()
            .get().uri("/binding").exchange().expectStatus().isOk().expectBody();
    }

    private void givenNoPersonalAccount(String username) {
        when(client.fetch(User.class, username)).thenReturn(Mono.just(cn.sky.ncm.me.MeFixture.testUser(username)));
        when(client.fetch(NcmAccount.class, AccountService.userAccountName(username))).thenReturn(Mono.empty());
    }

    private void givenAccount(String name, String owner, long userId) {
        var account = new NcmAccount();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(Instant.parse("2026-11-02T00:00:00Z"));
        account.setMetadata(metadata);
        var spec = new NcmAccount.Spec();
        spec.setOwner(owner);
        account.setSpec(spec);
        var status = new NcmAccount.Status();
        status.setUserId(userId);
        account.setStatus(status);
        when(client.fetch(NcmAccount.class, name)).thenReturn(Mono.just(account));
        when(client.fetch(User.class, owner)).thenReturn(Mono.just(cn.sky.ncm.me.MeFixture.testUser(owner)));
        when(secrets.readVersionedValue(AccountService.secretNameOf(name), "account", "cookie"))
            .thenReturn(Mono.just(new SecretStore.VersionedValue("MUSIC_U=fixture", "revision")));
    }
}

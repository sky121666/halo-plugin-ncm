package cn.sky.ncm.gateway;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.sky.ncm.account.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.test.StepVerifier;

class IdentityResolverTest {

    @Test
    void publicReadsUseGuestIdentityEvenForSignedInUsers() {
        var accounts = mock(AccountService.class);
        var resolver = new IdentityResolver(accounts);
        var viewer = new TestingAuthenticationToken("owner", null, "ROLE_super-role");
        StepVerifier.create(resolver.resolve("public")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(viewer)))
            .expectNext(Identity.anonymous()).verifyComplete();
        verifyNoInteractions(accounts);
    }
}

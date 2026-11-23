package cn.sky.ncm.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

class AccountServiceTest {

    static SecretBox box() throws Exception {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 5);
        // SecretBox(byte[], String) 是包内可见的测试构造器
        Constructor<SecretBox> c = SecretBox.class.getDeclaredConstructor(byte[].class, String.class);
        c.setAccessible(true);
        return c.newInstance(k, "test");
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyCookieIsMigratedToSecret() throws Exception {
        var client = mock(ReactiveExtensionClient.class);
        var box = box();
        var service = new AccountService(client, box, new SecretStore(client, box));

        var account = new NcmAccount();
        var m = new Metadata();
        m.setName("site");
        account.setMetadata(m);
        var spec = new NcmAccount.Spec();
        spec.setOwner("admin");
        spec.setCookie("MUSIC_U=legacy; __csrf=c");
        account.setSpec(spec);

        when(client.fetch(NcmAccount.class, "site")).thenReturn(Mono.just(account));
        when(client.fetch(Secret.class, "ncm-account-site")).thenReturn(Mono.empty());
        when(client.create(any(Secret.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(client.update(any(NcmAccount.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.cookieOf("site"))
            .assertNext(c -> assertEquals("legacy", c.get("MUSIC_U")))
            .verifyComplete();

        var secret = ArgumentCaptor.forClass(Secret.class);
        verify(client).create(secret.capture());
        assertTrue(SecretBox.isEncrypted(secret.getValue().getStringData().get("cookie")));

        var updated = ArgumentCaptor.forClass(NcmAccount.class);
        verify(client).update(updated.capture());
        assertNull(updated.getValue().getSpec().getCookie(), "迁移后 spec 里不能再有 Cookie");
        assertEquals("ncm-account-site", updated.getValue().getSpec().getSecretName());
    }

    @Test
    void secretNameIsDerivedFromAccountName() {
        assertEquals("ncm-account-site", AccountService.secretNameOf("site"));
        assertEquals("ncm-account-user-alice", AccountService.secretNameOf(AccountService.userAccountName("alice")));
    }
}

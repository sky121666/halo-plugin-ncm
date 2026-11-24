package cn.sky.ncm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.api.OpenApis;
import cn.sky.ncm.setting.BasicSetting;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.test.StepVerifier;

class OpenScopeTest {

    static BasicSetting basic(String scope, String extra) {
        return new BasicSetting(300, "", false, scope, extra);
    }

    @Test
    void defaultOpensOnlyCommonReadApis() {
        BasicSetting d = BasicSetting.DEFAULT;
        assertTrue(d.apiOpen("cloudsearch"));
        assertTrue(d.apiOpen("song_url_v1"));
        assertFalse(d.apiOpen("comment"));
        assertFalse(d.apiOpen("listentogether_room_create"));
        assertTrue(basic("all", "").apiOpen("comment"));
        BasicSetting extra = basic("common", "comment, like\n<bad>");
        assertEquals(Set.of("comment", "like"), extra.extraApiSet());
        assertTrue(extra.apiOpen("like"));
    }

    @Test
    void commonListContainsOnlyExistingReadApis() {
        var registry = new ApiRegistry();
        for (String name : OpenApis.COMMON) {
            var def = registry.find(name).orElseThrow(() -> new AssertionError("不存在的接口：" + name));
            assertTrue("public".equals(def.tier()) || "personal".equals(def.tier()), name + " 不是只读等级");
            assertTrue(def.executable(), name + " 不可用");
        }
    }

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

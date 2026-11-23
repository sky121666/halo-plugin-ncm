package cn.sky.ncm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.BasicSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

class NcmGatewayDebugTest {

    private final ApiRegistry registry = mock(ApiRegistry.class);
    private final ApiExecutor executor = mock(ApiExecutor.class);
    private final IdentityResolver resolver = mock(IdentityResolver.class);
    private final SettingService settings = mock(SettingService.class);
    private final ResponseCache cache = mock(ResponseCache.class);
    private final NcmBootstrap bootstrap = mock(NcmBootstrap.class);
    private final RateLimiter limiter = mock(RateLimiter.class);
    private final NcmGateway gateway = new NcmGateway(registry, executor, resolver, settings, cache, bootstrap, limiter);

    private final WebTestClient web = WebTestClient.bindToRouterFunction(RouterFunctions.route()
        .GET("/debug/{name}", r -> gateway.debug(r, "operator"))
        .POST("/debug/{name}", r -> gateway.debug(r, "operator"))
        .build()).build();

    @Test
    void blocksMutationsAndInternalOperationsBeforeResolvingCredentials() {
        when(limiter.tryAcquire("debug:operator", 30)).thenReturn(true);
        when(registry.find("register_anonimous"))
            .thenReturn(Optional.of(definition("register_anonimous", "admin")));
        web.get().uri("/debug/register_anonimous").exchange().expectStatus().isForbidden();
        web.method(HttpMethod.POST).uri("/debug/album").exchange().expectStatus().isEqualTo(405);
        verifyNoInteractions(resolver, executor, bootstrap, cache);
    }

    @Test
    void readonlyDebugUsesGuestBypassesSharedCacheAndRedactsResponse() {
        when(limiter.tryAcquire("debug:operator", 30)).thenReturn(true);
        when(registry.find("album")).thenReturn(Optional.of(definition("album", "public")));
        when(settings.basic()).thenReturn(Mono.just(BasicSetting.CLOSED));
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(bootstrap.ready()).thenReturn(Mono.empty());
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        body.putObject("album").put("name", "示例专辑").put("sessionToken", "fixture");
        when(executor.execute(any(ApiDef.class), anyMap(), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(new NcmResponse(200, body, List.of())));

        web.get().uri("/debug/album?id=1")
            .exchange().expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*")
            .expectBody().jsonPath("$.album.name").isEqualTo("示例专辑")
            .jsonPath("$.album.sessionToken").doesNotExist();
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(executor).execute(any(ApiDef.class), anyMap(), anyMap(), options.capture());
        assertThat(options.getValue().cookie()).isEmpty();
        verifyNoInteractions(resolver, cache);
    }

    private static ApiDef definition(String name, String tier) {
        return new ApiDef(name, tier, "ready", "", "", "", Map.of(), List.of(), "");
    }
}

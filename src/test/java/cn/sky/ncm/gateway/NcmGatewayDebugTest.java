package cn.sky.ncm.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.endpoint.NcmApiEndpoint;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.InetSocketAddress;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
        .build().and(new NcmApiEndpoint(gateway, registry).endpoint())).build();

    @Test
    void aRegisteredPublicReadUsesGuestAndConsumesItsQuota() {
        when(registry.find("album_list")).thenReturn(Optional.of(definition("album_list", "public")));
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(limiter.tryAcquire(startsWith("ip:"), eq(120))).thenReturn(true, false);
        when(limiter.tryAcquire("ncm:public-total", 600)).thenReturn(true);
        when(bootstrap.ready()).thenReturn(Mono.empty());
        when(resolver.resolve("public")).thenReturn(Mono.just(Identity.anonymous()));
        when(executor.execute(any(ApiDef.class), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(new NcmResponse(200, JsonNodeFactory.instance.objectNode().put("code", 200), List.of())));

        web.get().uri("/public/album_list").exchange().expectStatus().isOk();
        web.get().uri("/public/album_list").exchange().expectStatus().isEqualTo(429);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(executor).execute(any(ApiDef.class), anyMap(), options.capture());
        assertThat(options.getValue().cookie()).isEmpty();
    }

    @Test
    void aRegisteredWriteRequiresThePersonalPostRouteAndRequestHeader() {
        when(registry.find("like")).thenReturn(Optional.of(definition("like", "write")));

        web.get().uri("/public/like").exchange().expectStatus().isForbidden();
        web.get().uri("/personal/like").exchange().expectStatus().isForbidden();
        web.post().uri("/personal/like").exchange().expectStatus().isForbidden();
        verifyNoInteractions(resolver, executor);
    }

    @Test
    void anAuthorizedWriteUsesTheResolvedPersonalBinding() {
        var bound = new Identity("user", Map.of("MUSIC_U", "test-credential"));
        var auth = UsernamePasswordAuthenticationToken.authenticated("owner", "unused", List.of());
        when(registry.find("like")).thenReturn(Optional.of(definition("like", "write")));
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(limiter.tryAcquire("user:owner", 120)).thenReturn(true);
        when(bootstrap.ready()).thenReturn(Mono.empty());
        when(resolver.resolve("write")).thenReturn(Mono.just(bound));
        when(executor.execute(any(ApiDef.class), anyMap(), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(new NcmResponse(200, JsonNodeFactory.instance.objectNode().put("code", 200), List.of())));
        var authorized = WebTestClient.bindToRouterFunction(new NcmApiEndpoint(gateway, registry).endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))).build();

        authorized.post().uri("/personal/like").header(NcmGateway.REQUEST_HEADER, "1").exchange().expectStatus().isOk();
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(executor).execute(any(ApiDef.class), anyMap(), anyMap(), options.capture());
        assertThat(options.getValue().cookie()).isEqualTo(bound.cookie());
    }

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
        when(registry.find("album_list")).thenReturn(Optional.of(definition("album_list", "public")));
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(bootstrap.ready()).thenReturn(Mono.empty());
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        body.putObject("album").put("name", "示例专辑").put("sessionToken", "fixture");
        when(executor.execute(any(ApiDef.class), anyMap(), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(new NcmResponse(200, body, List.of())));

        web.get().uri("/debug/album_list?limit=1")
            .exchange().expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*")
            .expectBody().jsonPath("$.album.name").isEqualTo("示例专辑")
            .jsonPath("$.album.sessionToken").doesNotExist();
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(executor).execute(any(ApiDef.class), anyMap(), anyMap(), options.capture());
        assertThat(options.getValue().cookie()).isEmpty();
        verifyNoInteractions(resolver, cache);
    }

    @Test
    void authenticatedPersonalTrafficDoesNotConsumeTheSharedIpBucket() {
        var request = MockServerRequest.builder().remoteAddress(new InetSocketAddress("192.0.2.12", 1234)).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("reader", "unused", List.of());
        when(limiter.tryAcquire("user:reader", AdvancedSetting.DEFAULT.userLimit())).thenReturn(true);
        StepVerifier.create(gateway.rateLimit(request, definition("user_detail", "personal"), AdvancedSetting.DEFAULT)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))).expectNext(true).verifyComplete();

        verify(limiter).tryAcquire("user:reader", AdvancedSetting.DEFAULT.userLimit());
        verifyNoMoreInteractions(limiter);
    }

    private static ApiDef definition(String name, String tier) {
        return new ApiDef(name, tier, "ready", "", "", "", Map.of(), List.of(), "");
    }
}

package cn.sky.ncm.finder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.api.ApiDef;
import cn.sky.ncm.api.ApiExecutor;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.gateway.IdentityResolver;
import cn.sky.ncm.gateway.ResponseCache;
import cn.sky.ncm.setting.BasicSetting;
import cn.sky.ncm.setting.FinderSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FinderSourceTest {

    final ApiExecutor executor = mock(ApiExecutor.class);
    final IdentityResolver resolver = mock(IdentityResolver.class);
    final SettingService settings = mock(SettingService.class);
    final NcmBootstrap bootstrap = mock(NcmBootstrap.class);
    FinderSource source;

    @BeforeEach
    void setUp() {
        when(settings.basic()).thenReturn(Mono.just(BasicSetting.DEFAULT));
        when(settings.finder()).thenReturn(Mono.just(FinderSetting.DEFAULT));
        when(bootstrap.ready()).thenReturn(Mono.empty());
        source = new FinderSource(new ApiRegistry(), executor, resolver, settings, bootstrap, new ResponseCache());
    }

    @Test
    void cachesSuccessfulResponses() {
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        when(executor.execute(any(ApiDef.class), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(new NcmResponse(200, body, List.of())));
        StepVerifier.create(source.call("lyric_new", Map.of("id", "1"), FinderSource.As.PUBLIC))
            .expectNextCount(1).verifyComplete();
        StepVerifier.create(source.call("lyric_new", Map.of("id", "1"), FinderSource.As.PUBLIC))
            .expectNextCount(1).verifyComplete();
        verify(executor, times(1)).execute(any(ApiDef.class), anyMap(), any(RequestOptions.class));
    }

    @Test
    void errorsAndNon200BecomeEmpty() {
        when(executor.execute(any(ApiDef.class), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.error(new RuntimeException("network")))
            .thenReturn(Mono.just(NcmResponse.error(502, "bad")));
        StepVerifier.create(source.call("lyric_new", Map.of("id", "2"), FinderSource.As.PUBLIC)).verifyComplete();
        StepVerifier.create(source.call("lyric_new", Map.of("id", "2"), FinderSource.As.PUBLIC)).verifyComplete();
    }

    @Test
    void fetchDistinguishesErrorsFromEmpty() {
        when(executor.execute(any(ApiDef.class), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.error(new NullPointerException("boom")))
            .thenReturn(Mono.just(NcmResponse.error(502, "bad")))
            .thenReturn(Mono.just(NcmResponse.error(404, "歌单不存在")));
        StepVerifier.create(source.fetch("lyric_new", Map.of("id", "3"), FinderSource.As.PUBLIC))
            .expectError(NcmUnavailableException.class).verify();
        StepVerifier.create(source.fetch("lyric_new", Map.of("id", "3"), FinderSource.As.PUBLIC))
            .expectErrorMatches(e -> e instanceof NcmUnavailableException && e.getMessage().contains("502"))
            .verify();
        StepVerifier.create(source.fetch("lyric_new", Map.of("id", "3"), FinderSource.As.PUBLIC)).verifyComplete();
    }

    @Test
    void fetchDoesNotCacheFailures() {
        var body = JsonNodeFactory.instance.objectNode().put("code", 200);
        when(executor.execute(any(ApiDef.class), anyMap(), any(RequestOptions.class)))
            .thenReturn(Mono.just(NcmResponse.error(503, "down")))
            .thenReturn(Mono.just(new NcmResponse(200, body, List.of())));
        StepVerifier.create(source.fetch("lyric_new", Map.of("id", "4"), FinderSource.As.PUBLIC))
            .expectError(NcmUnavailableException.class).verify();
        StepVerifier.create(source.fetch("lyric_new", Map.of("id", "4"), FinderSource.As.PUBLIC))
            .expectNextCount(1).verifyComplete();
    }

    @Test
    void siteIdentityWithoutBindingIsEmpty() {
        when(resolver.site()).thenReturn(Mono.empty());
        StepVerifier.create(source.call("recommend_songs", Map.of(), FinderSource.As.SITE)).verifyComplete();
        verify(executor, times(0)).execute(any(ApiDef.class), anyMap(), any(RequestOptions.class));
    }

    @Test
    void unknownApiIsEmpty() {
        StepVerifier.create(source.call("no_such_api", Map.of(), FinderSource.As.PUBLIC)).verifyComplete();
    }
}

package cn.sky.ncm.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.api.CustomApis;
import cn.sky.ncm.api.more.UserApis;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.security.RateLimiter;
import cn.sky.ncm.setting.AdvancedSetting;
import cn.sky.ncm.setting.SettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PhoneLoginServiceTest {
    @Test
    void sameTargetSharesTheCaptchaBudgetAcrossHaloUsers() {
        var client = mock(NcmClient.class);
        var accounts = mock(AccountService.class);
        var settings = mock(SettingService.class);
        when(settings.advanced()).thenReturn(Mono.just(AdvancedSetting.DEFAULT));
        when(accounts.requireOwner("alice")).thenReturn(Mono.just(new AccountService.Owner("alice", Instant.EPOCH)));
        when(accounts.requireOwner("bob")).thenReturn(Mono.just(new AccountService.Owner("bob", Instant.EPOCH)));
        var result = new ObjectMapper().createObjectNode().put("code", 200);
        String endpoint = "/api/sms/captcha/sent";
        when(client.request(eq(endpoint), anyMap(), any()))
            .thenReturn(Mono.just(NcmResponse.ok(result, List.of())));
        var service = new PhoneLoginService(client, mock(UserApis.class), mock(CustomApis.class), accounts,
            settings, new RateLimiter());
        var request = MockServerRequest.builder().uri(URI.create("https://music.example.test")).build();
        var params = Map.of("phone", "12345", "ctcode", "86");

        StepVerifier.create(service.sendCaptcha(params, "alice", request))
            .assertNext(response -> assertEquals(200, response.path("code").asInt()))
            .verifyComplete();
        StepVerifier.create(service.sendCaptcha(params, "bob", request))
            .expectErrorSatisfies(error -> assertEquals(429, ((ResponseStatusException) error).getStatusCode().value()))
            .verify();
        verify(client, times(1)).request(eq(endpoint), anyMap(), any());
    }
}

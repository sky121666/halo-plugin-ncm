package cn.sky.ncm.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.sky.ncm.account.AccountService;
import cn.sky.ncm.account.PhoneLoginService;
import cn.sky.ncm.account.QrLoginService;
import cn.sky.ncm.api.ApiRegistry;
import cn.sky.ncm.bootstrap.NcmBootstrap;
import cn.sky.ncm.endpoint.dto.NcmCaptchaRequest;
import cn.sky.ncm.endpoint.dto.NcmCellphoneLoginRequest;
import cn.sky.ncm.gateway.NcmGateway;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EndpointsTest {

    @Test
    void guestStatusProjectsOnlySummaryWithoutStartingBootstrap() {
        var bootstrap = mock(NcmBootstrap.class);
        when(bootstrap.status()).thenReturn(JsonNodeFactory.instance.objectNode()
            .put("anonymousReady", true)
            .put("lastAttempt", "1970-01-01T00:00:00Z")
            .put("deviceId", "internal-device")
            .put("secretKeySource", "internal-detail"));
        var endpoint = new NcmConsoleEndpoint(mock(AccountService.class), mock(QrLoginService.class),
            mock(NcmGateway.class), bootstrap, mock(PhoneLoginService.class));
        WebTestClient.bindToRouterFunction(endpoint.endpoint()).build().get()
            .uri("/guest-status/-").exchange()
            .expectStatus().isOk()
            .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
            .expectBody().jsonPath("$.state").isEqualTo("registered")
            .jsonPath("$.deviceId").doesNotExist()
            .jsonPath("$.secretKeySource").doesNotExist();
        verify(bootstrap, never()).ready();
    }

    @Test
    void routersBuildWithOpenApiDocs() {
        assertNotNull(new NcmApiEndpoint(mock(NcmGateway.class), mock(ApiRegistry.class)).endpoint());
        assertNotNull(new NcmUcEndpoint(mock(AccountService.class), mock(QrLoginService.class),
            mock(PhoneLoginService.class)).endpoint());
        assertNotNull(new NcmConsoleEndpoint(mock(AccountService.class), mock(QrLoginService.class),
            mock(NcmGateway.class), mock(NcmBootstrap.class),
            mock(PhoneLoginService.class)).endpoint());
    }

    static MockServerRequest body(Object b) {
        return MockServerRequest.builder().body(Mono.just(b));
    }

    @Test
    void captchaBodyValidation() {
        var ok = new NcmCaptchaRequest();
        ok.setPhone(" 13800138000 ");
        StepVerifier.create(EndpointSupport.captchaBody(body(ok)))
            .assertNext(p -> {
                assertEquals("13800138000", p.get("phone"));
                assertEquals("86", p.get("ctcode"));
            })
            .verifyComplete();

        var bad = new NcmCaptchaRequest();
        bad.setPhone("abc");
        StepVerifier.create(EndpointSupport.captchaBody(body(bad))).expectError(ServerWebInputException.class).verify();
    }

    @Test
    void cellphoneBodyValidation() {
        var byCaptcha = new NcmCellphoneLoginRequest();
        byCaptcha.setPhone("13800138000");
        byCaptcha.setCaptcha("1234");
        byCaptcha.setPassword("ignored");
        StepVerifier.create(EndpointSupport.cellphoneBody(body(byCaptcha)))
            .assertNext(p -> {
                assertEquals("1234", p.get("captcha"));
                assertEquals(null, p.get("password"), "有验证码时不应再带上密码");
            })
            .verifyComplete();

        var none = new NcmCellphoneLoginRequest();
        none.setPhone("13800138000");
        StepVerifier.create(EndpointSupport.cellphoneBody(body(none))).expectError(ServerWebInputException.class).verify();

        var badCaptcha = new NcmCellphoneLoginRequest();
        badCaptcha.setPhone("13800138000");
        badCaptcha.setCaptcha("12ab");
        StepVerifier.create(EndpointSupport.cellphoneBody(body(badCaptcha)))
            .expectError(ServerWebInputException.class).verify();
    }
}

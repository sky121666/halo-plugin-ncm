package cn.sky.ncm.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.server.ResponseStatusException;

class NcmRequestSecurityTest {
    private static final URI PATH = URI.create("https://music.example.test/apis/test");

    @Test
    void sameOriginCookieAndNonBrowserHaloClientCanWrite() {
        var session = MockServerRequest.builder().uri(PATH).cookie(new HttpCookie("SESSION", "fixture"))
            .header("Origin", "https://music.example.test").header(NcmRequestSecurity.WRITE_HEADER, "1").build();
        assertDoesNotThrow(() -> NcmRequestSecurity.requireWrite(session));
        var client = MockServerRequest.builder().uri(PATH).header(NcmRequestSecurity.WRITE_HEADER, "1").build();
        assertDoesNotThrow(() -> NcmRequestSecurity.requireWrite(client));
    }

    @Test
    void browserSessionRequiresItsOriginAndPrivateReadsRejectOtherOrigins() {
        var session = MockServerRequest.builder().uri(PATH).cookie(new HttpCookie("SESSION", "fixture"))
            .header(NcmRequestSecurity.WRITE_HEADER, "1").build();
        assertThrows(ResponseStatusException.class, () -> NcmRequestSecurity.requireWrite(session));
        var other = MockServerRequest.builder().uri(PATH).header("Origin", "https://other.example.test").build();
        assertThrows(ResponseStatusException.class, () -> NcmRequestSecurity.requirePrivateOrigin(other));
    }
}

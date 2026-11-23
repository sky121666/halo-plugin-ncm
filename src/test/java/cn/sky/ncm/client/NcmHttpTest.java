package cn.sky.ncm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.sky.ncm.setting.ProxySettings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.transport.ProxyProvider;

class NcmHttpTest {

    @Test
    void parsesTheEndpointAndRejectsUserInformation() {
        var http = NcmHttp.parse("http://127.0.0.1:7890");
        assertEquals(ProxyProvider.Proxy.HTTP, http.type());
        assertEquals("127.0.0.1", http.host());
        assertEquals(7890, http.port());
        assertNull(http.username());

        var socks = NcmHttp.parse("socks5://127.0.0.1");
        assertEquals(ProxyProvider.Proxy.SOCKS5, socks.type());
        assertEquals(1080, socks.port());

        assertNull(NcmHttp.parse("http://fixture:" + "example" + "@127.0.0.1:7890"));
        assertNull(NcmHttp.parse("http://127.0.0.1:7890/path"));
        assertNull(NcmHttp.parse("ftp://example.invalid"));
        assertNull(NcmHttp.parse("not a url"));
    }

    @Test
    void authenticatesToTheProxyBeforeTheFirstRequest() throws Exception {
        try (var proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(4000);
            var details = CompletableFuture.supplyAsync(() -> receive(proxy));
            var settings = mock(ProxySettings.class);
            var endpoint = NcmHttp.parse("http://127.0.0.1:" + proxy.getLocalPort());
            when(settings.current()).thenReturn(Mono.just(Optional.of(endpoint.authenticate("fixture", "example"))));

            String response = new NcmHttp(settings).current().get().uri("http://ncm-fixture.invalid/")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

            assertEquals("ok", response);
            assertTrue(details.get(2, TimeUnit.SECONDS));
            assertFalse(endpoint.toString().contains("example"));
        }
    }

    private static boolean receive(ServerSocket proxy) {
        try (var socket = proxy.accept()) {
            socket.setSoTimeout(4000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var output = socket.getOutputStream();
            String target = input.readLine();
            String expected = "Basic " + Base64.getEncoder()
                .encodeToString("fixture:example".getBytes(StandardCharsets.US_ASCII));
            boolean authenticated = false;
            for (String header = input.readLine(); header != null && !header.isEmpty(); header = input.readLine()) {
                authenticated |= header.regionMatches(true, 0, "Proxy-Authorization: ", 0, 21)
                    && expected.equals(header.substring(21));
            }
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String request = input.readLine();
            for (String header = input.readLine(); header != null && !header.isEmpty(); header = input.readLine()) {
                // Consume headers from the tunneled request.
            }
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return target.startsWith("CONNECT ncm-fixture.invalid:80 ") && authenticated && request.startsWith("GET / ");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

package cn.sky.ncm.security;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Halo 负责认证和 RBAC。这里限制浏览器用会话访问插件的私有数据。 */
public final class NcmRequestSecurity {
    public static final String WRITE_HEADER = "X-NCM-Request";

    private NcmRequestSecurity() {
    }

    public static Mono<ServerResponse> privateEndpoint(ServerRequest request, HandlerFunction<ServerResponse> handler) {
        return Mono.defer(() -> {
            requirePrivateOrigin(request);
            return handler.handle(request);
        });
    }

    public static void requirePrivateOrigin(ServerRequest request) {
        var origins = request.headers().header(HttpHeaders.ORIGIN);
        String fetchSite = request.headers().firstHeader("Sec-Fetch-Site");
        if ("cross-site".equals(fetchSite) || "same-site".equals(fetchSite)
            || (!origins.isEmpty() && !sameOrigin(request))) {
            throw denied();
        }
    }

    public static void requireWrite(ServerRequest request) {
        requirePrivateOrigin(request);
        if (!"1".equals(request.headers().firstHeader(WRITE_HEADER))
            || (!request.cookies().isEmpty() && !sameOrigin(request))) {
            throw denied();
        }
    }

    public static void requireBrowserWrite(ServerRequest request) {
        requireWrite(request);
        if (!sameOrigin(request)) {
            throw denied();
        }
    }

    private static boolean sameOrigin(ServerRequest request) {
        var origins = request.headers().header(HttpHeaders.ORIGIN);
        if (origins.size() != 1) {
            return false;
        }
        try {
            URI origin = URI.create(origins.getFirst());
            URI target = request.uri();
            return origin.getHost() != null && origin.getHost().equalsIgnoreCase(target.getHost())
                && target.getScheme().equalsIgnoreCase(origin.getScheme()) && port(origin) == port(target)
                && origin.getRawUserInfo() == null && origin.getRawPath().isEmpty()
                && origin.getRawQuery() == null && origin.getRawFragment() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "请从本站页面发起请求");
    }
}

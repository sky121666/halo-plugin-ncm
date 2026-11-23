package cn.sky.ncm.client;

import cn.sky.ncm.setting.ProxySettings;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/** 共用的出站客户端，在发送请求前解析代理设置。 */
@Component
public class NcmHttp {

    public record ProxySpec(ProxyProvider.Proxy type, String host, int port, String username, String password) {
        public ProxySpec authenticate(String user, String pass) {
            return new ProxySpec(type, host, port, user, pass);
        }

        @Override
        public String toString() {
            return "ProxySpec[" + type + ", authenticated=" + (username != null) + "]";
        }
    }

    private static final ExchangeStrategies STRATEGIES = ExchangeStrategies.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    private final ProxySettings proxy;
    private final WebClient client;
    private ProxySpec currentProxy;
    private ExchangeFunction connector;

    public NcmHttp(ProxySettings proxy) {
        this.proxy = proxy;
        this.client = WebClient.builder().exchangeStrategies(STRATEGIES)
            .exchangeFunction(request -> proxy.current().flatMap(value -> select(value).exchange(request)))
            .build();
    }

    public WebClient current() {
        return client;
    }

    public String proxyStatus() {
        return proxy.status();
    }

    private synchronized ExchangeFunction select(Optional<ProxySpec> selected) {
        ProxySpec spec = selected.orElse(null);
        if (connector == null || !Objects.equals(spec, currentProxy)) {
            HttpClient http = HttpClient.create().followRedirect(false).responseTimeout(Duration.ofMinutes(5));
            if (spec != null) {
                http = http.proxy(builder -> {
                    var b = builder.type(spec.type()).host(spec.host()).port(spec.port());
                    if (spec.username() != null) {
                        b.username(spec.username()).password(user -> spec.password());
                    }
                });
            }
            connector = ExchangeFunctions.create(new ReactorClientHttpConnector(http), STRATEGIES);
            currentProxy = spec;
        }
        return connector;
    }

    /** 只解析地址。认证信息由 Halo Secret 单独提供。 */
    public static ProxySpec parse(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            ProxyProvider.Proxy type = switch (scheme) {
                case "http", "https" -> ProxyProvider.Proxy.HTTP;
                case "socks", "socks5", "socks5h" -> ProxyProvider.Proxy.SOCKS5;
                case "socks4" -> ProxyProvider.Proxy.SOCKS4;
                default -> null;
            };
            if (type == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() == 0
                || uri.getPort() > 65535 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : (type == ProxyProvider.Proxy.HTTP ? 80 : 1080);
            return new ProxySpec(type, uri.getHost(), port, null, null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

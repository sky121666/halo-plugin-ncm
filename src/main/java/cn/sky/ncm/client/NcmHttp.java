package cn.sky.ncm.client;

import cn.sky.ncm.setting.ProxySettings;
import io.netty.channel.ChannelOption;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
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
    private final String trustedProxyAuthorities;
    private final WebClient client;
    private ProxySpec currentProxy;
    private Mono<ExchangeFunction> connector;

    @Autowired
    public NcmHttp(ProxySettings proxy) {
        this(proxy, System.getenv("NCM_TRUSTED_OUTBOUND_PROXIES"));
    }

    NcmHttp(ProxySettings proxy, String trustedProxyAuthorities) {
        this.proxy = proxy;
        this.trustedProxyAuthorities = trustedProxyAuthorities;
        this.client = WebClient.builder().exchangeStrategies(STRATEGIES)
            .exchangeFunction(request -> proxy.current().flatMap(this::select).flatMap(selected -> selected.exchange(request)))
            .build();
    }

    public WebClient current() {
        return client;
    }

    public String proxyStatus() {
        return proxy.status();
    }

    private synchronized Mono<ExchangeFunction> select(Optional<ProxySpec> selected) {
        ProxySpec spec = selected.orElse(null);
        if (connector == null || !Objects.equals(spec, currentProxy)) {
            connector = spec == null ? Mono.just(connector(null, null))
                : Mono.defer(() -> NcmEgress.proxyAddress(spec, trustedProxyAuthorities)).map(address -> connector(spec, address))
                    .cache(Duration.ofMinutes(1));
            currentProxy = spec;
        }
        return connector;
    }

    private static ExchangeFunction connector(ProxySpec spec, InetSocketAddress address) {
        HttpClient http = HttpClient.create().followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 8000).responseTimeout(Duration.ofMinutes(5));
        if (spec != null) {
            http = http.proxy(builder -> {
                var proxy = builder.type(spec.type()).address(address).connectTimeoutMillis(8000);
                if (spec.username() != null) {
                    proxy.username(spec.username()).password(user -> spec.password());
                }
            });
        }
        return ExchangeFunctions.create(new ReactorClientHttpConnector(http), STRATEGIES);
    }

    /** 只解析地址。认证信息由 Halo Secret 单独提供。 */
    public static ProxySpec parse(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            ProxyProvider.Proxy type = switch (scheme) {
                case "http" -> ProxyProvider.Proxy.HTTP;
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

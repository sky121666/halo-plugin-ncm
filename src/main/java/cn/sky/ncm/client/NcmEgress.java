package cn.sky.ncm.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 核验出站地址后再建立连接或附带认证信息。 */
public final class NcmEgress {
    private static final Pattern NOS_HOST = Pattern.compile("nosup(?:-[a-z0-9]+)*\\.127\\.net");
    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(5);

    private NcmEgress() {
    }

    public static Mono<URI> cloudUploadOrigin(String address) {
        return Mono.defer(() -> {
            var uri = uploadOrigin(address);
            return resolve(uri.getHost(), 443, false).thenReturn(uri);
        });
    }

    static URI uploadOrigin(String address) {
        try {
            var uri = URI.create(address);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                && NOS_HOST.matcher(host.toLowerCase(Locale.ROOT)).matches()
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                return URI.create("https://" + host.toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // 统一返回不带服务器地址的错误。
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上传服务返回了无效地址");
    }

    static Mono<InetSocketAddress> proxyAddress(NcmHttp.ProxySpec proxy, String trusted) {
        String authority = proxy.host() + ":" + proxy.port();
        boolean explicit = trusted != null && Arrays.stream(trusted.split("[,\\s]+"))
            .anyMatch(authority::equalsIgnoreCase);
        if (proxy.username() != null && !explicit) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "认证代理需要部署端授权"));
        }
        return resolve(proxy.host(), proxy.port(), explicit);
    }

    private static Mono<InetSocketAddress> resolve(String host, int port, boolean allowPrivate) {
        return Mono.fromCallable(() -> InetAddress.getAllByName(host))
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(DNS_TIMEOUT)
            .filter(addresses -> addresses.length > 0
                && Arrays.stream(addresses).allMatch(address -> allowed(address, allowPrivate)))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "出站地址不受信任")))
            .map(addresses -> new InetSocketAddress(addresses[0], port))
            .onErrorMap(error -> !(error instanceof ResponseStatusException),
                error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "出站地址解析失败"));
    }

    static boolean allowed(InetAddress address, boolean allowPrivate) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
            || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return allowPrivate;
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xe0) == 0x20;
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
            && !(first == 198 && (second == 18 || second == 19));
    }
}

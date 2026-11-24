package cn.sky.ncm.setting;

import static java.nio.charset.StandardCharsets.UTF_8;

import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmHttp.ProxySpec;
import cn.sky.ncm.security.SecretStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** 校验代理地址和密钥引用，并迁移旧版本 URL 中的认证信息。 */
@Component
public class ProxySettings {

    static final String CONFIG = "plugin-ncm-configmap";
    private static final String GROUP = "advanced";
    private static final String PURPOSE = "proxy-credentials";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE = Duration.ofSeconds(10);

    private record Selection(String address, String secret) {
    }

    private final ReactiveSettingFetcher fetcher;
    private final ReactiveExtensionClient client;
    private final SecretStore secrets;
    private final Mono<Optional<ProxySpec>> selection;
    private volatile String status = "";

    public ProxySettings(ReactiveSettingFetcher fetcher, ReactiveExtensionClient client, SecretStore secrets) {
        this.fetcher = fetcher;
        this.client = client;
        this.secrets = secrets;
        this.selection = Mono.defer(this::load)
            .doOnNext(spec -> status = spec.isPresent() ? "已配置" : "")
            .doOnError(error -> status = "配置无效")
            .cache(CACHE);
    }

    public Mono<Optional<ProxySpec>> current() {
        return selection;
    }

    public String status() {
        return status;
    }

    private Mono<Optional<ProxySpec>> load() {
        return fetcher.fetch(GROUP, AdvancedSetting.class)
            .defaultIfEmpty(AdvancedSetting.DEFAULT)
            .flatMap(setting -> prepare(clean(setting.proxy()), clean(setting.proxySecret())))
            .flatMap(this::resolve)
            .onErrorMap(error -> !(error instanceof ResponseStatusException),
                error -> invalid("无法读取网易云出站代理配置"));
    }

    private Mono<Selection> prepare(String address, String secret) {
        if (address.isEmpty()) {
            return Mono.just(new Selection(address, secret));
        }
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            return Mono.error(invalid("出站代理地址无效"));
        }
        if (uri.getRawUserInfo() == null) {
            return Mono.just(new Selection(address, secret));
        }
        return migrate(address, uri, secret);
    }

    private Mono<Optional<ProxySpec>> resolve(Selection selected) {
        if (selected.address().isEmpty() && selected.secret().isEmpty()) {
            return Mono.just(Optional.empty());
        }
        ProxySpec spec = NcmHttp.parse(selected.address());
        if (spec == null) {
            return Mono.error(invalid("请填写不含用户名和密码的 HTTP 或 SOCKS 代理地址"));
        }
        if (selected.secret().isEmpty()) {
            return Mono.just(Optional.of(spec));
        }
        return client.fetch(Secret.class, selected.secret())
            .switchIfEmpty(Mono.error(invalid("找不到出站代理密钥")))
            .flatMap(secret -> SecretStore.owned(secret)
                ? secrets.read(selected.secret(), PURPOSE)
                : Mono.fromSupplier(() -> data(secret)))
            .defaultIfEmpty(Map.of())
            .map(values -> {
                String username = values.get("username");
                String password = values.get("password");
                if (values.size() != 2 || username == null || username.isBlank() || password == null || password.isEmpty()) {
                    throw invalid("出站代理请使用仅含 username 和 password 的专用 Secret");
                }
                return Optional.of(spec.authenticate(username, password));
            });
    }

    private Mono<Selection> migrate(String original, URI uri, String chosenSecret) {
        String endpoint = addressWithoutUserInfo(uri);
        if (NcmHttp.parse(endpoint) == null) {
            return Mono.error(invalid("旧代理地址无效，请在「网络与限流」检查代理地址"));
        }
        String[] info = uri.getUserInfo().split(":", 2);
        if (chosenSecret.isEmpty() && (info.length != 2 || info[0].isBlank() || info[1].isEmpty())) {
            return Mono.error(invalid("请将旧代理的账号和密码保存到 Halo Secret"));
        }
        return client.fetch(ConfigMap.class, CONFIG)
            .switchIfEmpty(Mono.error(invalid("无法读取旧代理配置")))
            .flatMap(config -> {
                ObjectNode advanced = advanced(config);
                if (!original.equals(clean(advanced.path("proxy").asText()))
                    || !chosenSecret.equals(clean(advanced.path("proxySecret").asText()))) {
                    return Mono.error(invalid("代理设置已变化，请重试"));
                }
                String target = chosenSecret.isEmpty() ? "plugin-ncm-proxy-" + UUID.randomUUID() : chosenSecret;
                Mono<Void> save = chosenSecret.isEmpty() ? secrets.write(target, PURPOSE, "网易云出站代理",
                    Map.of("username", info[0], "password", info[1])) : Mono.empty();
                advanced.put("proxy", endpoint).put("proxySecret", target);
                Map<String, String> updated = new LinkedHashMap<>(config.getData());
                updated.put(GROUP, advanced.toString());
                config.setData(updated);
                // ConfigMap 的资源版本防止覆盖同时保存的其他配置。
                return save.then(Mono.defer(() -> client.update(config))).thenReturn(new Selection(endpoint, target));
            });
    }

    private static ObjectNode advanced(ConfigMap config) {
        try {
            if (config.getData() != null && JSON.readTree(config.getData().getOrDefault(GROUP, "{}")) instanceof ObjectNode node) {
                return node;
            }
        } catch (JsonProcessingException e) {
            throw invalid("旧代理配置格式无效");
        }
        throw invalid("无法读取旧代理配置");
    }

    private static String addressWithoutUserInfo(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            throw invalid("旧代理地址无效，请在「网络与限流」检查代理地址");
        }
    }

    private static Map<String, String> data(Secret secret) {
        Map<String, String> values = new LinkedHashMap<>();
        if (secret.getData() != null) {
            secret.getData().forEach((k, v) -> {
                if (v != null) {
                    values.put(k, new String(v, UTF_8));
                }
            });
        }
        if (secret.getStringData() != null) {
            values.putAll(secret.getStringData());
        }
        return values;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}

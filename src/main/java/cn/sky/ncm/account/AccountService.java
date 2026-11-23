package cn.sky.ncm.account;

import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 网易云账号的存取。
 * <ul>
 *   <li>NcmAccount 只保存昵称、头像等展示信息和 Secret 名称</li>
 *   <li>Cookie 保存在 Halo Secret「ncm-account-{账号名}」里，值再经 SecretBox 加密</li>
 *   <li>旧版本存在 NcmAccount.spec.cookie 里的 Cookie，第一次读取时自动迁移到 Secret 并清空</li>
 * </ul>
 * Secret 名称总是由账号名推导，不信任 spec 里记录的名称，避免被改成指向其他 Secret。
 * 解密失败（加密密钥更换）的账号视为未绑定，需要重新绑定。
 */
@Slf4j
@Component
public class AccountService {

    public static final String SITE = "site";
    public static final String SITE_OWNER = "__site__";
    static final String COOKIE_KEY = "cookie";
    static final String PURPOSE = "account";

    private final ReactiveExtensionClient client;
    private final SecretBox box;
    private final SecretStore secrets;

    public AccountService(ReactiveExtensionClient client, SecretBox box, SecretStore secrets) {
        this.client = client;
        this.box = box;
        this.secrets = secrets;
    }

    public static String userAccountName(String username) {
        return "user-" + username;
    }

    public static String secretNameOf(String accountName) {
        return "ncm-account-" + accountName;
    }

    static String description(String accountName) {
        String who = SITE.equals(accountName) ? "站长账号"
            : "用户 " + (accountName.startsWith("user-") ? accountName.substring(5) : accountName) + " 的账号";
        return "网易云音乐插件：" + who + "的登录 Cookie（由插件管理，值已加密，请勿手动修改）";
    }

    public Mono<NcmAccount> find(String name) {
        return client.fetch(NcmAccount.class, name);
    }

    public Mono<Map<String, String>> cookieOf(String name) {
        return credentialsOf(name).map(Credentials::cookie);
    }

    public Mono<Credentials> credentialsOf(String name) {
        return find(name).flatMap(this::decode);
    }

    /** 仅返回此 Halo 用户在后台绑定的站长账号。 */
    public Mono<NcmAccount> siteAccountIfOwnedBy(String username) {
        return find(SITE)
            .filter(a -> a.getSpec() != null && username.equals(a.getSpec().getOwner()));
    }

    public Mono<Map<String, String>> siteCookieIfOwnedBy(String username) {
        return siteCredentialsIfOwnedBy(username).map(Credentials::cookie);
    }

    public Mono<Credentials> siteCredentialsIfOwnedBy(String username) {
        return siteAccountIfOwnedBy(username).flatMap(this::decode);
    }

    /** revision 为空表示凭据来自旧版记录，当前请求直接取址。 */
    public record Credentials(Map<String, String> cookie, String revision) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    /** 取出可用的 Cookie；旧版数据顺便迁移到 Secret；解密失败返回空。 */
    @SuppressWarnings("deprecation")
    Mono<Credentials> decode(NcmAccount a) {
        if (a.getSpec() != null && a.getSpec().getCookie() != null && !a.getSpec().getCookie().isBlank()) {
            return migrate(a).map(cookie -> new Credentials(cookie, null));
        }
        String name = a.getMetadata().getName();
        return secrets.readVersionedValue(secretNameOf(name), COOKIE_KEY)
            .filter(value -> !value.value().isBlank())
            .map(value -> new Credentials(parseCookie(value.value()), value.revision()))
            .onErrorResume(SecretBox.SecretException.class, e -> {
                log.warn("[ncm] 账号 {} 的 Cookie 无法解密（加密密钥可能已更换），需要重新绑定", name);
                return Mono.empty();
            });
    }

    @SuppressWarnings("deprecation")
    Mono<Map<String, String>> migrate(NcmAccount a) {
        String name = a.getMetadata().getName();
        String plain;
        try {
            plain = box.decrypt(a.getSpec().getCookie());
        } catch (SecretBox.SecretException e) {
            log.warn("[ncm] 账号 {} 的旧版 Cookie 无法解密，需要重新绑定", name);
            return Mono.empty();
        }
        Map<String, String> cookie = parseCookie(plain);
        return secrets.write(secretNameOf(name), PURPOSE, description(name), Map.of(COOKIE_KEY, plain))
            .then(Mono.defer(() -> {
                a.getSpec().setCookie(null);
                a.getSpec().setSecretName(secretNameOf(name));
                return client.update(a);
            }))
            .doOnNext(x -> log.info("[ncm] 账号 {} 的 Cookie 已迁移到 Halo Secret {}", name, secretNameOf(name)))
            .thenReturn(cookie)
            .onErrorResume(e -> {
                log.warn("[ncm] 账号 {} 的 Cookie 迁移到 Secret 失败，下次读取时重试", name, e);
                return Mono.just(cookie);
            });
    }

    /** 账号是否能正常读出 Cookie。 */
    @SuppressWarnings("deprecation")
    public Mono<Boolean> readable(NcmAccount a) {
        if (a == null) {
            return Mono.just(false);
        }
        if (a.getSpec() != null && a.getSpec().getCookie() != null && !a.getSpec().getCookie().isBlank()) {
            try {
                box.decrypt(a.getSpec().getCookie());
                return Mono.just(true);
            } catch (SecretBox.SecretException e) {
                return Mono.just(false);
            }
        }
        return secrets.read(secretNameOf(a.getMetadata().getName()))
            .map(m -> m.containsKey(COOKIE_KEY))
            .defaultIfEmpty(false)
            .onErrorReturn(SecretBox.SecretException.class, false);
    }

    /** 登录成功后保存（或覆盖）账号：Cookie 写进 Secret，NcmAccount 只记名字。 */
    @SuppressWarnings("deprecation")
    public Mono<NcmAccount> save(String name, String owner, List<String> setCookies) {
        String cookie = setCookiesToHeader(setCookies);
        String secretName = secretNameOf(name);
        return secrets.write(secretName, PURPOSE, description(name), Map.of(COOKIE_KEY, cookie))
            .then(find(name)
                .flatMap(existing -> {
                    var spec = existing.getSpec() == null ? new NcmAccount.Spec() : existing.getSpec();
                    spec.setOwner(owner);
                    spec.setSecretName(secretName);
                    spec.setCookie(null);
                    existing.setSpec(spec);
                    existing.setStatus(new NcmAccount.Status());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var account = new NcmAccount();
                    var metadata = new Metadata();
                    metadata.setName(name);
                    account.setMetadata(metadata);
                    var spec = new NcmAccount.Spec();
                    spec.setOwner(owner);
                    spec.setSecretName(secretName);
                    account.setSpec(spec);
                    account.setStatus(new NcmAccount.Status());
                    return client.create(account);
                })));
    }

    /** 用 login_status 的返回结果补全昵称、头像等。 */
    public Mono<NcmAccount> updateProfile(String name, JsonNode loginStatusBody) {
        JsonNode profile = loginStatusBody.path("data").path("profile");
        JsonNode account = loginStatusBody.path("data").path("account");
        return find(name).flatMap(a -> {
            var status = a.getStatus() == null ? new NcmAccount.Status() : a.getStatus();
            if (!profile.isMissingNode() && !profile.isNull()) {
                status.setUserId(profile.path("userId").asLong());
                status.setNickname(profile.path("nickname").asText(null));
                status.setAvatarUrl(profile.path("avatarUrl").asText(null));
                status.setVipType(profile.path("vipType").asInt(account.path("vipType").asInt(0)));
            }
            status.setBoundAt(Instant.now().toString());
            a.setStatus(status);
            return client.update(a);
        });
    }

    /** 解绑：删除账号和对应的 Secret。 */
    public Mono<Void> delete(String name) {
        return find(name).flatMap(client::delete).then(secrets.delete(secretNameOf(name)));
    }

    public Mono<ObjectNode> bindingView(NcmAccount account) {
        return readable(account).map(readable -> view(account).put("readable", readable));
    }

    public static ObjectNode view(NcmAccount a) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("bound", a != null);
        if (a != null && a.getStatus() != null) {
            var s = a.getStatus();
            n.put("userId", s.getUserId());
            n.put("nickname", s.getNickname());
            n.put("avatarUrl", s.getAvatarUrl());
            n.put("vipType", s.getVipType());
            n.put("boundAt", s.getBoundAt());
        }
        return n;
    }

    public static Map<String, String> parseCookie(String cookie) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookie == null) {
            return map;
        }
        for (String part : cookie.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                map.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return map;
    }

    /** Set-Cookie 列表 → "a=1; b=2"，只保留 name=value，丢掉 Path/Expires 等属性和空值。 */
    static String setCookiesToHeader(List<String> setCookies) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String c : setCookies) {
            String first = c.split(";", 2)[0];
            int i = first.indexOf('=');
            if (i > 0) {
                String v = first.substring(i + 1).trim();
                if (!v.isEmpty()) {
                    map.put(first.substring(0, i).trim(), v);
                }
            }
        }
        var joiner = new StringJoiner("; ");
        map.forEach((k, v) -> joiner.add(k + "=" + v));
        return joiner.toString();
    }
}

package cn.sky.ncm.account;

import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Halo 用户、网易云资料与版本化 Secret 之间的绑定。 */
@Slf4j
@Component
public class AccountService {

    public static final String SITE = "site";
    static final String COOKIE_KEY = "cookie";
    static final String PURPOSE = "account";
    private static final Duration PROFILE_TIMEOUT = Duration.ofSeconds(10);

    private final ReactiveExtensionClient client;
    private final SecretBox box;
    private final SecretStore secrets;

    public AccountService(ReactiveExtensionClient client, SecretBox box, SecretStore secrets) {
        this.client = client;
        this.box = box;
        this.secrets = secrets;
    }

    public record Owner(String username, Instant createdAt) {
    }

    public record BindingChange(String name, Owner owner, Optional<NcmAccount> previous) {
        @Override
        public String toString() {
            return "BindingChange[" + name + "]";
        }
    }

    public record Credentials(Map<String, String> cookie, String revision) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public record Binding(NcmAccount account, Optional<Credentials> credentials) {
        public boolean site() {
            return SITE.equals(account.getMetadata().getName());
        }
    }

    public static String userAccountName(String username) {
        return "user-" + username;
    }

    /** 兼容仍引用固定名 Secret 的现有绑定。 */
    public static String secretNameOf(String accountName) {
        return "ncm-account-" + accountName;
    }

    private static String versionPrefix(String accountName) {
        return "ncm-binding-" + UUID.nameUUIDFromBytes(accountName.getBytes(StandardCharsets.UTF_8)) + "-";
    }

    private Mono<NcmAccount> find(String name) {
        return client.fetch(NcmAccount.class, name);
    }

    private Mono<Owner> owner(String username) {
        return client.fetch(User.class, username)
            .filter(user -> user.getSpec() == null || !Boolean.TRUE.equals(user.getSpec().getDisabled()))
            .map(User::getMetadata)
            .filter(metadata -> metadata.getDeletionTimestamp() == null && metadata.getCreationTimestamp() != null)
            .map(metadata -> new Owner(username, metadata.getCreationTimestamp()));
    }

    public Mono<Owner> requireOwner(String username) {
        return owner(username).switchIfEmpty(Mono.error(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录 Halo")));
    }

    public Mono<Binding> binding(String username) {
        return owner(username).flatMap(owner -> owned(userAccountName(username), owner)
            .switchIfEmpty(Mono.defer(() -> owned(SITE, owner)))
            .flatMap(account -> snapshot(account, owner)));
    }

    public Mono<NcmAccount> siteAccount() {
        return find(SITE).filter(AccountService::active)
            .filterWhen(account -> owner(account.getSpec().getOwner()).map(owner -> belongsTo(account, owner)));
    }

    public Mono<Credentials> siteCredentials() {
        return find(SITE).filter(AccountService::active).flatMap(account -> owner(account.getSpec().getOwner())
            .filter(owner -> belongsTo(account, owner))
            .flatMap(owner -> decode(account, owner)));
    }

    public Mono<ObjectNode> siteBindingView() {
        return find(SITE).filter(AccountService::active)
            .flatMap(account -> owner(account.getSpec().getOwner())
                .filter(owner -> belongsTo(account, owner))
                .flatMap(owner -> snapshot(account, owner))
                .map(binding -> view(account).put("readable", binding.credentials().isPresent()))
                .defaultIfEmpty(view(account).put("readable", false)))
            .defaultIfEmpty(view(null));
    }

    private Mono<NcmAccount> owned(String name, Owner owner) {
        return find(name).filter(account -> belongsTo(account, owner));
    }

    private static boolean active(NcmAccount account) {
        return account.getMetadata().getDeletionTimestamp() == null && account.getSpec() != null
            && !account.getSpec().isRevoked() && account.getSpec().getOwner() != null;
    }

    static boolean belongsTo(NcmAccount account, Owner owner) {
        if (!active(account) || !owner.username().equals(account.getSpec().getOwner())) {
            return false;
        }
        String epoch = account.getSpec().getOwnerCreatedAt();
        if (epoch != null) {
            return owner.createdAt().toString().equals(epoch);
        }
        // 旧记录只接受能证明绑定在当前 Halo 用户创建之后的记录。
        Instant recordCreatedAt = account.getMetadata().getCreationTimestamp();
        return recordCreatedAt != null && !recordCreatedAt.isBefore(owner.createdAt());
    }

    private Mono<Binding> snapshot(NcmAccount account, Owner owner) {
        return decode(account, owner).map(Optional::of).defaultIfEmpty(Optional.empty())
            .map(credentials -> new Binding(account, credentials));
    }

    public static ObjectNode bindingView(Binding binding) {
        var result = view(binding.account()).put("readable", binding.credentials().isPresent());
        if (binding.site()) {
            result.put("managedInConsole", true);
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private Mono<Credentials> decode(NcmAccount account, Owner owner) {
        String name = account.getMetadata().getName();
        String legacy = account.getSpec().getCookie();
        Mono<Credentials> result;
        if (legacy != null && !legacy.isBlank()) {
            result = Mono.defer(() -> {
                String cookie = box.decrypt(legacy);
                if (!hasSession(parseCookie(cookie))) {
                    return Mono.empty();
                }
                var change = new BindingChange(name, owner, Optional.of(account));
                return saveSnapshot(change, cookie, account.getStatus()).flatMap(this::readSecret);
            });
        } else {
            result = readSecret(account);
        }
        return result.onErrorResume(SecretBox.SecretException.class, error -> {
            log.warn("[ncm] 账号凭据无法解密，需要重新绑定");
            return Mono.empty();
        });
    }

    private Mono<Credentials> readSecret(NcmAccount account) {
        return Mono.justOrEmpty(secretReference(account))
            .flatMap(name -> secrets.readVersionedValue(name, PURPOSE, COOKIE_KEY))
            .map(value -> new Credentials(Map.copyOf(parseCookie(value.value())), value.revision()))
            .filter(credentials -> hasSession(credentials.cookie()));
    }

    private static String secretReference(NcmAccount account) {
        if (!active(account)) return null;
        String name = account.getMetadata().getName();
        String reference = account.getSpec().getSecretName();
        if (reference == null || reference.equals(secretNameOf(name))) return secretNameOf(name);
        return reference.startsWith(versionPrefix(name)) ? reference : null;
    }

    public Mono<BindingChange> begin(String name, Owner owner) {
        if (!SITE.equals(name) && !userAccountName(owner.username()).equals(name)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "账号归属无效"));
        }
        return find(name).map(Optional::of).defaultIfEmpty(Optional.empty())
            .map(previous -> new BindingChange(name, owner, previous));
    }

    public Mono<NcmAccount> save(BindingChange change, List<String> setCookies,
                                 Function<RequestOptions, Mono<NcmResponse>> verify) {
        return Mono.defer(() -> {
            String cookie = setCookiesToHeader(setCookies);
            var values = parseCookie(cookie);
            if (!hasSession(values)) {
                return Mono.error(invalidSession());
            }
            return verify.apply(new RequestOptions("", Map.copyOf(values), null, null, false))
                .timeout(PROFILE_TIMEOUT)
                .map(AccountService::verifiedProfile)
                .flatMap(profile -> saveSnapshot(change, cookie, profile));
        });
    }

    private Mono<NcmAccount> saveSnapshot(BindingChange change, String cookie, NcmAccount.Status status) {
        if (!box.persistent()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "账号密钥尚未持久化，暂时无法保存绑定"));
        }
        String secretName = versionPrefix(change.name()) + UUID.randomUUID();
        return requireCurrent(change.owner())
            .then(secrets.write(secretName, PURPOSE, "网易云账号会话", Map.of(COOKIE_KEY, cookie)))
            .then(Mono.defer(() -> requireCurrent(change.owner()).then(publish(change, secretName, status))))
            .onErrorResume(error -> find(change.name())
                .filter(current -> secretName.equals(secretReference(current)))
                .switchIfEmpty(cleanup(secretName).then(Mono.error(error))))
            .flatMap(saved -> cleanupPrevious(change).thenReturn(saved));
    }

    /** 留下撤销版本，使已经生成的二维码或正在登录的旧请求无法恢复绑定。 */
    public Mono<Void> revoke(String name, String username) {
        return requireOwner(username).flatMap(owner -> begin(name, owner))
            .flatMap(change -> requireCurrent(change.owner())
                .then(publish(change, null, null))
                .then(cleanupPrevious(change)));
    }

    private Mono<Void> requireCurrent(Owner expected) {
        return requireOwner(expected.username()).filter(expected::equals)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号身份已变化，请重新登录 Halo")))
            .then();
    }

    private Mono<NcmAccount> publish(BindingChange change, String secretName, NcmAccount.Status status) {
        return Mono.defer(() -> {
            var account = new NcmAccount();
            var metadata = new Metadata();
            metadata.setName(change.name());
            change.previous().map(NcmAccount::getMetadata).ifPresent(previous -> {
                metadata.setVersion(previous.getVersion());
                metadata.setCreationTimestamp(previous.getCreationTimestamp());
                metadata.setDeletionTimestamp(previous.getDeletionTimestamp());
                metadata.setLabels(previous.getLabels());
                metadata.setAnnotations(previous.getAnnotations());
                metadata.setFinalizers(previous.getFinalizers());
            });
            account.setMetadata(metadata);
            var spec = new NcmAccount.Spec();
            spec.setOwner(change.owner().username());
            spec.setOwnerCreatedAt(change.owner().createdAt().toString());
            spec.setSecretName(secretName);
            spec.setRevoked(secretName == null);
            account.setSpec(spec);
            account.setStatus(status);
            if (change.previous().isPresent()) {
                return client.update(account);
            }
            return client.create(account).onErrorResume(error -> find(change.name())
                .flatMap(existing -> Mono.<NcmAccount>error(conflict()))
                .switchIfEmpty(Mono.error(error)));
        }).onErrorMap(OptimisticLockingFailureException.class, error -> conflict());
    }

    private Mono<Void> cleanupPrevious(BindingChange change) {
        return cleanup(change.previous().map(AccountService::secretReference).orElse(null));
    }

    private Mono<Void> cleanup(String secretName) {
        return Mono.justOrEmpty(secretName).flatMap(name -> secrets.delete(name, PURPOSE))
            .onErrorResume(error -> {
                log.warn("[ncm] 旧账号凭据清理失败");
                return Mono.empty();
            });
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "绑定已变化，请重新开始绑定");
    }

    private static ResponseStatusException invalidSession() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "网易云未能确认该账号，请重新授权");
    }

    private static boolean hasSession(Map<String, String> cookie) {
        return !cookie.getOrDefault("MUSIC_U", "").isBlank();
    }

    private static NcmAccount.Status verifiedProfile(NcmResponse response) {
        JsonNode body = response.body().path("data");
        JsonNode profile = body.path("profile");
        JsonNode account = body.path("account");
        long uid = profile.path("userId").asLong(account.path("id").asLong());
        if (!response.ok() || uid <= 0) {
            throw invalidSession();
        }
        var status = new NcmAccount.Status();
        status.setUserId(uid);
        status.setNickname(profile.path("nickname").asText(null));
        status.setAvatarUrl(profile.path("avatarUrl").asText(null));
        status.setVipType(profile.path("vipType").asInt(account.path("vipType").asInt(0)));
        status.setBoundAt(Instant.now().toString());
        return status;
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

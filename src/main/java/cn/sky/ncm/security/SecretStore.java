package cn.sky.ncm.security;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/**
 * 把凭据存进 Halo Secret（按 Halo 插件安全规范，凭据不放在自定义模型的 spec 里）。
 * <p>
 * Halo 的 Secret 在数据库里只是 JSON，并不加密，所以写入前仍用 {@link SecretBox} 加密每个值：
 * 单独泄露数据库备份时看不到明文，Secret 也只有超级管理员能通过 Halo 的接口读取。
 * <p>
 * 只读写带有本插件标签的 Secret；同名但不属于本插件的 Secret 一律不读、不覆盖。
 */
@Component
public class SecretStore {

    public static final String LABEL_PLUGIN = "plugin.halo.run/plugin-name";
    public static final String PLUGIN_NAME = "plugin-ncm";
    public static final String LABEL_PURPOSE = "ncm.halo.run/purpose";
    /** Halo Console 密钥列表里显示的备注。 */
    public static final String ANNOTATION_DESCRIPTION = "secret.halo.run/description";

    private final ReactiveExtensionClient client;
    private final SecretBox box;

    public SecretStore(ReactiveExtensionClient client, SecretBox box) {
        this.client = client;
        this.box = box;
    }

    public static boolean owned(Secret s) {
        var labels = s.getMetadata() == null ? null : s.getMetadata().getLabels();
        return labels != null && PLUGIN_NAME.equals(labels.get(LABEL_PLUGIN));
    }

    /** ConfigMap 是否由本插件创建（带本插件标签）。 */
    public static boolean ownedConfigMap(ConfigMap c) {
        var labels = c.getMetadata() == null ? null : c.getMetadata().getLabels();
        return labels != null && PLUGIN_NAME.equals(labels.get(LABEL_PLUGIN));
    }

    /**
     * 读取并解密。Secret 不存在或不属于本插件时为空。
     *
     * @return 发出 {@link SecretBox.SecretException} 表示密钥不对（加密密钥已更换）
     */
    public Mono<Map<String, String>> read(String name) {
        return encryptedValues(name).map(raw -> {
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    out.put(k, box.decrypt(v));
                }
            });
            return out;
        });
    }

    /** 解密值与版本来自同一次读取。随机 IV 使同一凭据重新保存时也得到新版本。 */
    public Mono<VersionedValue> readVersionedValue(String name, String key) {
        return encryptedValues(name)
            .mapNotNull(values -> values.get(key))
            .filter(value -> !value.isEmpty())
            .map(value -> new VersionedValue(box.decrypt(value), HexFormat.of().formatHex(SecretBox.sha256(value))));
    }

    public record VersionedValue(String value, String revision) {
        @Override
        public String toString() {
            return "VersionedValue[redacted]";
        }
    }

    private Mono<Map<String, String>> encryptedValues(String name) {
        return client.fetch(Secret.class, name)
            .filter(SecretStore::owned)
            .map(s -> {
                Map<String, String> raw = new LinkedHashMap<>();
                if (s.getData() != null) {
                    s.getData().forEach((k, v) -> raw.put(k, v == null ? null : new String(v, UTF_8)));
                }
                if (s.getStringData() != null) {
                    raw.putAll(s.getStringData());
                }
                return raw;
            });
    }

    /** 整体覆盖写入（空值会被丢弃）。不存在时创建。 */
    public Mono<Void> write(String name, String purpose, String description, Map<String, String> values) {
        Map<String, String> enc = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                enc.put(k, box.encrypt(v));
            }
        });
        return Mono.defer(() -> client.fetch(Secret.class, name)
                .flatMap(existing -> {
                    if (!owned(existing)) {
                        return Mono.error(new IllegalStateException(
                            "Secret " + name + " 已存在，但不是网易云音乐插件创建的，已拒绝覆盖"));
                    }
                    existing.setType(Secret.SECRET_TYPE_OPAQUE);
                    existing.setData(null);
                    existing.setStringData(enc);
                    existing.getMetadata().setAnnotations(withDescription(existing.getMetadata().getAnnotations(),
                        description));
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var s = new Secret();
                    var m = new Metadata();
                    m.setName(name);
                    m.setLabels(new HashMap<>(Map.of(LABEL_PLUGIN, PLUGIN_NAME, LABEL_PURPOSE, purpose)));
                    m.setAnnotations(withDescription(null, description));
                    s.setMetadata(m);
                    s.setType(Secret.SECRET_TYPE_OPAQUE);
                    s.setStringData(enc);
                    return client.create(s);
                })))
            // 并发保存时可能遇到版本冲突，重新读取后再试
            .retryWhen(Retry.max(2).filter(e -> !(e instanceof IllegalStateException)))
            .then();
    }

    public Mono<Void> delete(String name) {
        return client.fetch(Secret.class, name)
            .filter(SecretStore::owned)
            .flatMap(client::delete)
            .then();
    }

    static Map<String, String> withDescription(Map<String, String> annotations, String description) {
        Map<String, String> a = annotations == null ? new HashMap<>() : new HashMap<>(annotations);
        a.put(ANNOTATION_DESCRIPTION, description);
        return a;
    }
}

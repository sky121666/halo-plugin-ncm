package cn.sky.ncm.device;

import cn.sky.ncm.client.DeviceState;
import cn.sky.ncm.security.SecretBox;
import cn.sky.ncm.security.SecretStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 把 DeviceState 存进 Halo：非敏感字段放 NcmDevice/default，
 * 游客 token、xeapi sk、xeapi 会话密钥放 Halo Secret「ncm-device-default」（值再经 SecretBox 加密）。
 * 变更后 2 秒内合并保存一次。旧版本存在 NcmDevice 里的敏感字段会在启动时自动迁移到 Secret。
 */
@Slf4j
@Component
public class DeviceStore {

    public static final String NAME = "default";
    public static final String SECRET_NAME = "ncm-device-default";
    static final String PURPOSE = "device";
    static final String DESCRIPTION = "网抑云管理的游客身份和 xeapi 会话密钥（已加密）";
    static final String K_ANONYMOUS = "anonymousToken";
    static final String K_XEAPI_SK = "xeapiSk";
    static final String K_XEAPI_SESSION = "xeapiSessionKey";
    static final Duration DEBOUNCE = Duration.ofSeconds(2);

    private final ReactiveExtensionClient client;
    private final DeviceState state;
    private final SecretBox box;
    private final SecretStore secrets;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Mono<Void> loaded;
    private volatile boolean restoring;
    private volatile String lastSaved;
    private volatile String lastError = "";

    public DeviceStore(ReactiveExtensionClient client, DeviceState state, SecretBox box, SecretStore secrets) {
        this.client = client;
        this.state = state;
        this.box = box;
        this.secrets = secrets;
        this.loaded = Mono.defer(this::doLoad).cache();
        state.onChange(this::scheduleSave);
    }

    /** 从 Halo 恢复（只执行一次）。 */
    public Mono<Void> load() {
        return loaded;
    }

    Mono<Void> doLoad() {
        return client.fetch(NcmDevice.class, NAME)
            .flatMap(d -> secrets.read(SECRET_NAME, PURPOSE)
                .onErrorResume(SecretBox.SecretException.class, e -> {
                    lastError = "设备 Secret 无法解密（加密密钥已更换），已重新获取游客身份";
                    return Mono.just(Map.of());
                })
                .defaultIfEmpty(Map.of())
                .doOnNext(sec -> restore(d, sec)))
            .onErrorResume(e -> {
                lastError = "读取设备状态失败: " + e.getMessage();
                log.warn("[ncm] {}", lastError, e);
                return Mono.empty();
            })
            .then();
    }

    @SuppressWarnings("deprecation")
    void restore(NcmDevice d, Map<String, String> sec) {
        var s = d.getSpec();
        if (s == null) {
            return;
        }
        boolean legacy = notEmpty(s.getAnonymousToken()) || notEmpty(s.getXeapiSk())
            || notEmpty(s.getXeapiSessionKey());
        restoring = true;
        try {
            String sk = first(sec.get(K_XEAPI_SK), legacyDec(s.getXeapiSk()));
            DeviceState.XeapiPublicKey key = s.getXeapiPublicKey() == null ? null
                : new DeviceState.XeapiPublicKey(s.getXeapiPublicKey(), s.getXeapiKeyVersion(), sk);
            state.restore(new DeviceState.Snapshot(s.getDeviceId(), s.getWnmcid(), s.getNmtid(),
                first(sec.get(K_ANONYMOUS), legacyDec(s.getAnonymousToken())), key,
                s.getXeapiFetchedAt() == null ? 0 : s.getXeapiFetchedAt(), s.getXeapiSessionId(),
                first(sec.get(K_XEAPI_SESSION), legacyDec(s.getXeapiSessionKey()))));
        } finally {
            restoring = false;
        }
        if (legacy) {
            log.info("[ncm] 发现旧版本保存在 NcmDevice 里的敏感字段，正在迁移到 Halo Secret {}", SECRET_NAME);
            scheduleSave();
        }
    }

    /** 旧版本字段可能是明文或 enc:v1；解密失败当作没有。 */
    String legacyDec(String v) {
        try {
            return box.decrypt(v);
        } catch (SecretBox.SecretException e) {
            lastError = "旧版设备状态中的加密字段无法解密，已重新获取";
            return null;
        }
    }

    void scheduleSave() {
        if (restoring || !scheduled.compareAndSet(false, true)) {
            return;
        }
        Mono.delay(DEBOUNCE)
            .then(Mono.defer(() -> {
                scheduled.set(false);
                return saveNow();
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(x -> {
            }, e -> log.warn("[ncm] 保存设备状态失败", e));
    }

    /** 先写 Secret，再写 NcmDevice（同时清掉旧版本的敏感字段）。 */
    public Mono<Void> saveNow() {
        var snap = state.snapshot();
        Map<String, String> sec = new LinkedHashMap<>();
        sec.put(K_ANONYMOUS, snap.anonymousToken());
        sec.put(K_XEAPI_SK, snap.xeapiPublicKey() == null ? null : snap.xeapiPublicKey().sk());
        sec.put(K_XEAPI_SESSION, snap.xeapiSessionKey());
        return secrets.write(SECRET_NAME, PURPOSE, DESCRIPTION, sec)
            .then(Mono.defer(() -> client.fetch(NcmDevice.class, NAME)
                    .flatMap(existing -> {
                        existing.setSpec(toSpec(snap));
                        return client.update(existing);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        var d = new NcmDevice();
                        var m = new Metadata();
                        m.setName(NAME);
                        d.setMetadata(m);
                        d.setSpec(toSpec(snap));
                        return client.create(d);
                    })))
                .retryWhen(Retry.max(2)))
            .doOnNext(d -> {
                lastSaved = d.getSpec().getUpdatedAt();
                lastError = "";
            })
            .onErrorResume(e -> {
                lastError = "保存设备状态失败: " + e.getMessage();
                log.warn("[ncm] {}", lastError);
                return Mono.empty();
            })
            .then();
    }

    /** 只包含非敏感字段；旧版本的敏感字段在这里被清空。 */
    static NcmDevice.Spec toSpec(DeviceState.Snapshot s) {
        var spec = new NcmDevice.Spec();
        spec.setDeviceId(s.deviceId());
        spec.setWnmcid(s.wnmcid());
        spec.setNmtid(s.nmtid());
        if (s.xeapiPublicKey() != null) {
            spec.setXeapiPublicKey(s.xeapiPublicKey().publicKey());
            spec.setXeapiKeyVersion(s.xeapiPublicKey().version());
            spec.setXeapiFetchedAt(s.xeapiFetchedAt());
        }
        spec.setXeapiSessionId(s.xeapiSessionId());
        spec.setSecretName(SECRET_NAME);
        spec.setUpdatedAt(Instant.now().toString());
        return spec;
    }

    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    static String first(String a, String b) {
        return notEmpty(a) ? a : b;
    }

    public String lastSaved() {
        return lastSaved;
    }

    public String lastError() {
        return lastError;
    }
}

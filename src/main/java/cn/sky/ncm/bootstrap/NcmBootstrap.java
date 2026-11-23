package cn.sky.ncm.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;

import cn.sky.ncm.client.DeviceState;
import cn.sky.ncm.client.NcmHttp;
import cn.sky.ncm.client.NcmClient;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.client.RequestOptions;
import cn.sky.ncm.crypto.NcmCrypto;
import cn.sky.ncm.crypto.XeapiCrypto;
import cn.sky.ncm.device.DeviceStore;
import cn.sky.ncm.security.SecretBox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 对应上游 generateConfig.js：获取 xeapi 公钥 → 注册游客（拿 MUSIC_A）。
 * <p>
 * 上游是先注册游客、再取公钥，但游客注册本身走 xeapi，首次启动时会因为没有公钥而失败；这里改为先取公钥。
 * 第一次调用任意接口时自动执行：先从数据库恢复设备状态，缺什么再补什么；xeapi 公钥超过 24 小时会重新获取。
 * 失败不影响 weapi / eapi 接口，5 分钟后再次调用时会重试。
 */
@Slf4j
@Component
public class NcmBootstrap {

    static final String ID_XOR_KEY = "3go8&$8*3*3h0k(2)2";
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    static final Duration XEAPI_KEY_TTL = Duration.ofHours(24);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NcmClient client;
    private final DeviceState device;
    private final DeviceStore store;
    private final SecretBox box;
    private final NcmHttp http;
    private final AtomicReference<Mono<Void>> ready = new AtomicReference<>();
    private volatile Instant lastAttempt = Instant.EPOCH;
    private volatile String lastError = "";

    public NcmBootstrap(NcmClient client, DeviceStore store, SecretBox box, NcmHttp http) {
        this.client = client;
        this.device = client.device();
        this.store = store;
        this.box = box;
        this.http = http;
    }

    boolean keyFresh() {
        return device.xeapiPublicKey() != null
            && System.currentTimeMillis() - device.xeapiFetchedAt() < XEAPI_KEY_TTL.toMillis();
    }

    /** 确保已初始化；永远不会抛错。 */
    public Mono<Void> ready() {
        boolean complete = keyFresh() && device.anonymousToken() != null;
        Mono<Void> current = ready.get();
        if (current != null && !complete && Instant.now().isAfter(lastAttempt.plus(RETRY_AFTER))) {
            ready.compareAndSet(current, null);
            current = null;
        }
        if (current == null) {
            Mono<Void> fresh = Mono.defer(this::init).cache();
            current = ready.compareAndSet(null, fresh) ? fresh : ready.get();
        }
        return current;
    }

    /** 恢复已保存的设备状态，无需请求网易云。 */
    public Mono<Void> restoreStoredState() {
        return store.load();
    }

    /** 强制重新获取 xeapi 公钥并重新注册游客（后台“刷新设备状态”）。 */
    public Mono<ObjectNode> refresh() {
        return store.load().then(Mono.defer(() -> {
            ready.set(null);
            force = true;
            return ready();
        })).then(Mono.fromSupplier(this::status));
    }

    /** 换一个全新的设备身份（deviceId 也重新生成），用于设备被风控时。 */
    public Mono<ObjectNode> reset() {
        return store.load().then(Mono.fromRunnable(device::reset)).then(refresh());
    }

    private volatile boolean force;

    Mono<Void> init() {
        lastAttempt = Instant.now();
        lastError = "";
        boolean forced = force;
        force = false;
        return store.load().then(Mono.defer(() -> forced || !keyFresh() ? fetchKeyStep() : Mono.empty()))
            .then(Mono.defer(() -> device.xeapiPublicKey() == null
                || (!forced && device.anonymousToken() != null) ? Mono.empty() : registerStep()))
            .then(Mono.defer(store::saveNow));
    }

    Mono<Void> fetchKeyStep() {
        return fetchXeapiPublicKey()
            .doOnNext(device::xeapiPublicKey)
            .onErrorResume(e -> {
                lastError = "xeapi 公钥: " + e.getMessage();
                log.warn("[ncm] 获取 xeapi 公钥失败", e);
                return Mono.empty();
            })
            .then();
    }

    Mono<Void> registerStep() {
        return registerAnonymous()
            .doOnNext(r -> {
                String token = r.path("musicA").asText("");
                if (!token.isEmpty()) {
                    device.anonymousToken(token);
                }
            })
            .onErrorResume(e -> {
                lastError = lastError + " 游客注册: " + e.getMessage();
                log.warn("[ncm] 游客注册失败", e);
                return Mono.empty();
            })
            .then();
    }

    /** 对应 module/register_xeapikey.js。 */
    public Mono<DeviceState.XeapiPublicKey> fetchXeapiPublicKey() {
        String nonce = randomDigits(16);
        String timestamp = String.valueOf(System.currentTimeMillis());
        var current = device.xeapiPublicKey();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appVersion", "9.5.61");
        data.put("currentKeyVersion", current == null ? "" : current.version());
        data.put("deviceId", device.deviceId());
        data.put("nonce", nonce);
        data.put("os", "android");
        data.put("requestType", "active");
        data.put("signature", XeapiCrypto.sign(timestamp, nonce));
        data.put("t1", "");
        data.put("t2", "");
        data.put("timestamp", timestamp);
        data.put("uid", "");
        return http.current().post().uri(NcmClient.API_DOMAIN + "/api/bsr/sk/get")
            .header("User-Agent", NcmClient.UA_ANDROID)
            .header("Cookie", "deviceId=" + NcmClient.encodeURIComponent(device.deviceId()))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(XeapiCrypto.urlSearchParams(data))
            .retrieve().bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
            .map(text -> {
                JsonNode res = readJson(text);
                JsonNode d = res.path("data");
                if (res.path("code").asInt() != 200 || d.path("encryptedData").asText("").isEmpty()) {
                    throw new IllegalStateException("xeapi public key request failed: " + text);
                }
                String sig = d.path("signature").asText("");
                if (sig.isEmpty() || !XeapiCrypto.sign(d.path("timestamp").asText(), nonce).equals(sig)) {
                    throw new IllegalStateException("xeapi public key response signature mismatch");
                }
                JsonNode pk = readJson(XeapiCrypto.decryptPublicKey(d.path("encryptedData").asText()));
                String sk = pk.path("sk").asText("");
                if (sk.isEmpty() && current != null) {
                    sk = current.sk();
                }
                if (sk == null || sk.isEmpty()) {
                    throw new IllegalStateException("xeapi public key response missing sk");
                }
                return new DeviceState.XeapiPublicKey(pk.path("publicKey").asText(), pk.path("version").asText(), sk);
            });
    }

    /** 对应 module/register_anonimous.js；返回 { code, musicA, cookie }。 */
    public Mono<ObjectNode> registerAnonymous() {
        String deviceId = device.deviceId();
        String username = Base64.getEncoder()
            .encodeToString((deviceId + " " + encodeId(deviceId)).getBytes(UTF_8));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        return client.request("/api/register/anonimous", data,
                new RequestOptions("xeapi", Map.of(), null, null, false))
            .map(this::anonymousResult);
    }

    ObjectNode anonymousResult(NcmResponse r) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("code", r.body().path("code").asInt(r.status()));
        String musicA = "";
        for (String c : r.cookies()) {
            String first = c.split(";", 2)[0].trim();
            if (first.startsWith("MUSIC_A=")) {
                musicA = first.substring("MUSIC_A=".length());
            }
        }
        out.put("musicA", musicA);
        out.put("cookie", String.join(";", r.cookies()));
        if (musicA.isEmpty()) {
            out.set("body", r.body());
        }
        return out;
    }

    /** 对应 cloudmusic_dll_encode_id：逐字符异或后 MD5，再 base64。 */
    static String encodeId(String id) {
        var sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            sb.append((char) (id.charAt(i) ^ ID_XOR_KEY.charAt(i % ID_XOR_KEY.length())));
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(sb.toString().getBytes(UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 后台展示用，不含任何密钥。 */
    public ObjectNode status() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("deviceId", device.deviceId());
        n.put("xeapiReady", device.xeapiPublicKey() != null);
        n.put("xeapiKeyVersion", device.xeapiPublicKey() == null ? null : device.xeapiPublicKey().version());
        n.put("xeapiSession", !device.xeapiSessionKey().isEmpty());
        n.put("anonymousReady", device.anonymousToken() != null);
        n.put("nmtidReady", device.nmtid() != null);
        n.put("xeapiKeyFetchedAt", device.xeapiFetchedAt() == 0 ? null : Instant.ofEpochMilli(device.xeapiFetchedAt()).toString());
        n.put("lastAttempt", lastAttempt.toString());
        n.put("lastError", lastError);
        n.put("persistedAt", store.lastSaved());
        n.put("storeError", store.lastError());
        n.put("credentialSecrets", DeviceStore.SECRET_NAME + "、ncm-account-*");
        n.put("secretKeySource", box.source());
        n.put("secretKeyPersistent", box.persistent());
        n.put("proxy", http.proxyStatus());
        return n;
    }

    static String randomDigits(int n) {
        var sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("invalid json: " + text, e);
        }
    }

    static {
        // 保证 NcmCrypto 的静态初始化（RSA 公钥）在启动阶段完成
        NcmCrypto.md5Hex("");
    }
}

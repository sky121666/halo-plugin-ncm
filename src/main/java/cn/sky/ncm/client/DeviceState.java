package cn.sky.ncm.client;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 设备身份：deviceId、WNMCID、NMTID、游客 token、xeapi 公钥与会话。
 * 启动时由 DeviceStore 从 Halo 数据库恢复；任何字段变化都会通知 DeviceStore 保存。
 */
@Component
public class DeviceState {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** xeapi 服务端公钥：publicKey 为 base64 的 32 字节 X25519 公钥。 */
    public record XeapiPublicKey(String publicKey, String version, String sk) {
    }

    /** 持久化用的快照。 */
    public record Snapshot(String deviceId, String wnmcid, String nmtid, String anonymousToken,
                           XeapiPublicKey xeapiPublicKey, long xeapiFetchedAt, String xeapiSessionId,
                           String xeapiSessionKey) {
    }

    private volatile String deviceId;
    private volatile String wnmcid;
    private final AtomicInteger nmtidRetries = new AtomicInteger(3);
    private volatile String nmtid;
    private volatile String anonymousToken;
    private volatile XeapiPublicKey xeapiPublicKey;
    private volatile long xeapiFetchedAt;
    private volatile String xeapiSessionId = "";
    private volatile String xeapiSessionKey = "";
    private volatile Runnable onChange = () -> {
    };

    public DeviceState() {
        this.deviceId = randomChars("0123456789ABCDEF", 52);
        this.wnmcid = randomChars("abcdefghijklmnopqrstuvwxyz", 6) + "." + System.currentTimeMillis() + ".01.0";
    }

    static String randomChars(String alphabet, int len) {
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public void onChange(Runnable listener) {
        this.onChange = listener == null ? () -> {
        } : listener;
    }

    private void changed() {
        onChange.run();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(deviceId, wnmcid, nmtid, anonymousToken, xeapiPublicKey, xeapiFetchedAt,
            xeapiSessionId, xeapiSessionKey);
    }

    /** 用数据库里的快照覆盖当前状态（不触发保存）。空字段保留当前值。 */
    public synchronized void restore(Snapshot s) {
        if (notEmpty(s.deviceId())) {
            deviceId = s.deviceId();
        }
        if (notEmpty(s.wnmcid())) {
            wnmcid = s.wnmcid();
        }
        if (notEmpty(s.nmtid())) {
            nmtid = s.nmtid();
        }
        if (notEmpty(s.anonymousToken())) {
            anonymousToken = s.anonymousToken();
        }
        if (s.xeapiPublicKey() != null && notEmpty(s.xeapiPublicKey().publicKey())) {
            xeapiPublicKey = s.xeapiPublicKey();
            xeapiFetchedAt = s.xeapiFetchedAt();
        }
        if (notEmpty(s.xeapiSessionKey())) {
            xeapiSessionId = s.xeapiSessionId() == null ? "" : s.xeapiSessionId();
            xeapiSessionKey = s.xeapiSessionKey();
        }
    }

    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    public String deviceId() {
        return deviceId;
    }

    public String wnmcid() {
        return wnmcid;
    }

    public String nmtid() {
        return nmtid;
    }

    public void nmtid(String value) {
        this.nmtid = value;
        changed();
    }

    public AtomicInteger nmtidRetries() {
        return nmtidRetries;
    }

    public String anonymousToken() {
        return anonymousToken;
    }

    public void anonymousToken(String value) {
        this.anonymousToken = value;
        changed();
    }

    public XeapiPublicKey xeapiPublicKey() {
        return xeapiPublicKey;
    }

    public long xeapiFetchedAt() {
        return xeapiFetchedAt;
    }

    public void xeapiPublicKey(XeapiPublicKey value) {
        this.xeapiPublicKey = value;
        this.xeapiFetchedAt = System.currentTimeMillis();
        changed();
    }

    public String xeapiSessionId() {
        return xeapiSessionId;
    }

    public String xeapiSessionKey() {
        return xeapiSessionKey;
    }

    /** 上游：响应头 x-encr-ssid / x-encr-sskey 同时出现时更新（全局共享）。 */
    public void xeapiSession(String id, String key) {
        synchronized (this) {
            if (id.equals(xeapiSessionId) && key.equals(xeapiSessionKey)) {
                return;
            }
            this.xeapiSessionId = id;
            this.xeapiSessionKey = key;
        }
        changed();
    }

    /** 清空游客身份和 xeapi 状态（后台“重置设备”）；deviceId 也重新生成。 */
    public void reset() {
        synchronized (this) {
            deviceId = randomChars("0123456789ABCDEF", 52);
            wnmcid = randomChars("abcdefghijklmnopqrstuvwxyz", 6) + "." + System.currentTimeMillis() + ".01.0";
            nmtid = null;
            nmtidRetries.set(3);
            anonymousToken = null;
            xeapiPublicKey = null;
            xeapiFetchedAt = 0;
            xeapiSessionId = "";
            xeapiSessionKey = "";
        }
        changed();
    }
}

package cn.sky.ncm.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import cn.sky.ncm.client.Js;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对应上游 util/crypto.js 中的 xeapi 系列：
 * <pre>
 * C = AES-ECB(dynamicKey, midTransform(AES-256-ECB(staticKey, plaintext)))      → base64url
 * S = ephemeralPub(32) | iv(12) | AES-128-GCM(HKDF(X25519(eph, serverPub)), "b64(dynamicKey)|os|sk") | tag(16)
 * R = AES-256-ECB(staticKey, "version|sessionId")
 * </pre>
 */
public final class XeapiCrypto {

    static final byte[] STATIC_KEY = HexFormat.of()
        .parseHex("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");
    /** 上游把这个 base64 字符串本身（UTF-8 字节）当作 HMAC 密钥，并没有先解码。 */
    static final byte[] SIGN_KEY =
        "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==".getBytes(UTF_8);
    static final byte[] X25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100");
    static final byte[] EAPI_KEY = "e82ckenh8dichen8".getBytes(UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final IntFunction<byte[]> SECURE_RANDOM = n -> {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    };

    private XeapiCrypto() {
    }

    /** 服务端公钥状态（来自 /api/bsr/sk/get）。 */
    public record PublicKeyState(String publicKey, String version, String sk) {
    }

    public static Map<String, String> encrypt(String uri, Map<String, Object> data, PublicKeyState state,
                                              String sessionId, String sessionKey, String os) {
        return encrypt(uri, data, state, sessionId, sessionKey, os, SECURE_RANDOM);
    }

    /** random 可注入，便于和 Node 版做对比测试。 */
    public static Map<String, String> encrypt(String uri, Map<String, Object> data, PublicKeyState state,
                                              String sessionId, String sessionKey, String os,
                                              IntFunction<byte[]> random) {
        if (state == null) {
            throw new IllegalStateException("xeapi public key is missing");
        }
        boolean hasSession = sessionKey != null && !sessionKey.isEmpty();
        byte[] dynamicKey = hasSession ? sessionKey.getBytes(UTF_8) : random.apply(16);
        byte[] plaintext = buildPlaintext(uri, data).getBytes(UTF_8);

        byte[] c = aesEcb(Cipher.ENCRYPT_MODE, dynamicKey,
            midTransform(aesEcb(Cipher.ENCRYPT_MODE, STATIC_KEY, plaintext), random));
        byte[] s = encryptS(dynamicKey, state, os == null ? "android" : os, random);
        byte[] r = aesEcb(Cipher.ENCRYPT_MODE, STATIC_KEY,
            (state.version() + "|" + (hasSession ? nullToEmpty(sessionId) : "")).getBytes(UTF_8));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("C", Base64.getUrlEncoder().withoutPadding().encodeToString(c));
        out.put("S", Base64.getEncoder().encodeToString(s));
        out.put("R", Base64.getEncoder().encodeToString(r));
        return out;
    }

    /** 对应 buildXeapiPlaintext（固定 POST + application/x-www-form-urlencoded）。 */
    static String buildPlaintext(String uri, Map<String, Object> data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String query = URI.create("https://interface.music.163.com" + uri).getRawQuery();
        if (query != null && !query.isEmpty()) {
            fields.put("queryString", query);
        }
        if (data != null) {
            Map<String, Object> body = new LinkedHashMap<>(data);
            body.remove("e_r");
            String encoded = urlSearchParams(body);
            if (!encoded.isEmpty()) {
                fields.put("content", encoded);
            }
        }
        if (fields.containsKey("queryString")) {
            fields.put("queryString", fields.get("queryString") + "&e_r=true");
        } else {
            fields.put("queryString", "e_r=true");
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 等价于 new URLSearchParams(obj).toString()：值按 String(v) 转换。 */
    public static String urlSearchParams(Map<String, Object> body) {
        var joiner = new StringJoiner("&");
        body.forEach((k, v) -> joiner.add(URLEncoder.encode(k, UTF_8) + "=" + URLEncoder.encode(Js.string(v), UTF_8)));
        return joiner.toString();
    }

    static byte[] midTransform(byte[] ciphertext, IntFunction<byte[]> random) {
        byte[] rnd = random.apply(16);
        int len = ciphertext.length;
        byte[] xored = new byte[len];
        for (int i = 0; i < len; i++) {
            xored[i] = (byte) (ciphertext[i] ^ rnd[i & 0x0f]);
        }
        int rot = len == 0 ? 0 : (rnd[0] & 0x0f) % len;
        byte[] out = new byte[16 + len];
        System.arraycopy(rnd, 0, out, 0, 16);
        System.arraycopy(xored, rot, out, 16, len - rot);
        System.arraycopy(xored, 0, out, 16 + len - rot, rot);
        return out;
    }

    /** midTransform 的逆运算（测试与调试用）。 */
    static byte[] midTransformInverse(byte[] data) {
        byte[] rnd = Arrays.copyOfRange(data, 0, 16);
        int len = data.length - 16;
        int rot = len == 0 ? 0 : (rnd[0] & 0x0f) % len;
        byte[] xored = new byte[len];
        System.arraycopy(data, 16 + len - rot, xored, 0, rot);
        System.arraycopy(data, 16, xored, rot, len - rot);
        for (int i = 0; i < len; i++) {
            xored[i] = (byte) (xored[i] ^ rnd[i & 0x0f]);
        }
        return xored;
    }

    static byte[] encryptS(byte[] dynamicKey, PublicKeyState state, String os, IntFunction<byte[]> random) {
        try {
            PublicKey peer = x25519PublicKey(Base64.getDecoder().decode(state.publicKey()));
            KeyPair eph = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] ephRaw = rawX25519(eph.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(eph.getPrivate());
            ka.doPhase(peer, true);
            byte[] shared = ka.generateSecret();
            byte[] aesKey = deriveAesKey(shared, ephRaw);
            byte[] iv = random.apply(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            String plain = Base64.getEncoder().encodeToString(dynamicKey) + "|" + os + "|" + nullToEmpty(state.sk());
            byte[] sealed = cipher.doFinal(plain.getBytes(UTF_8)); // 密文 + 16 字节 tag，与上游拼接顺序一致
            byte[] out = new byte[32 + 12 + sealed.length];
            System.arraycopy(ephRaw, 0, out, 0, 32);
            System.arraycopy(iv, 0, out, 32, 12);
            System.arraycopy(sealed, 0, out, 44, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 与上游 deriveX25519AesKey 相同：HMAC(0^32, shared) → HMAC(prk, ephPub|0x01)[0..16]。 */
    static byte[] deriveAesKey(byte[] shared, byte[] ephRaw) {
        byte[] prk = hmac(new byte[32], shared.length > 0 ? shared : new byte[32]);
        byte[] info = Arrays.copyOf(ephRaw, ephRaw.length + 1);
        info[ephRaw.length] = 1;
        return Arrays.copyOf(hmac(prk, info), 16);
    }

    static PublicKey x25519PublicKey(byte[] raw) throws GeneralSecurityException {
        byte[] spki = Arrays.copyOf(X25519_SPKI_PREFIX, X25519_SPKI_PREFIX.length + raw.length);
        System.arraycopy(raw, 0, spki, X25519_SPKI_PREFIX.length, raw.length);
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    /** SPKI 编码的最后 32 字节就是原始公钥（与上游 .subarray(-32) 相同）。 */
    static byte[] rawX25519(PublicKey key) {
        byte[] enc = key.getEncoded();
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    /** 对应 xeapiSign：HMAC-SHA256(SIGN_KEY, timestamp + nonce) → base64。 */
    public static String sign(String timestamp, String nonce) {
        return Base64.getEncoder().encodeToString(hmac(SIGN_KEY, (timestamp + nonce).getBytes(UTF_8)));
    }

    /** 对应 xeapiResDecrypt：AES-128-ECB(eapiKey) 解密，gzip 时解压。 */
    public static String decryptResponse(byte[] body) {
        byte[] plain = aesEcb(Cipher.DECRYPT_MODE, EAPI_KEY, body);
        if (plain.length > 1 && (plain[0] & 0xff) == 0x1f && (plain[1] & 0xff) == 0x8b) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(plain))) {
                plain = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return new String(plain, UTF_8);
    }

    /** 对应 xeapiDecryptPublicKey。 */
    public static String decryptPublicKey(String encryptedData) {
        return new String(aesEcb(Cipher.DECRYPT_MODE, STATIC_KEY, Base64.getDecoder().decode(encryptedData)), UTF_8);
    }

    static byte[] aesEcb(int mode, byte[] key, byte[] input) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(mode, new SecretKeySpec(key, "AES"));
            return c.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

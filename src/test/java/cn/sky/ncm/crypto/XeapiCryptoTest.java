package cn.sky.ncm.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class XeapiCryptoTest {

    static final IntFunction<byte[]> ZERO = byte[]::new;

    @Test
    void midTransformIsReversible() {
        byte[] data = "hello xeapi, 1234567890".getBytes(UTF_8);
        byte[] out = XeapiCrypto.midTransform(data, XeapiCrypto.SECURE_RANDOM);
        assertArrayEquals(data, XeapiCrypto.midTransformInverse(out));
    }

    @Test
    void plaintextMatchesUpstreamShape() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ids", "[186016]");
        data.put("level", "standard");
        data.put("e_r", false);
        assertEquals("{\"content\":\"ids=%5B186016%5D&level=standard\",\"queryString\":\"e_r=true\"}",
            XeapiCrypto.buildPlaintext("/api/song/enhance/player/url/v1", data));
    }

    /** 自洽测试：用测试生成的“服务端”密钥对，能完整解出 C、S、R。 */
    @Test
    void serverCanDecryptEverything() throws Exception {
        KeyPair server = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        String serverPub = Base64.getEncoder().encodeToString(XeapiCrypto.rawX25519(server.getPublic()));
        var state = new XeapiCrypto.PublicKeyState(serverPub, "7", "SK");
        Map<String, Object> data = new LinkedHashMap<>(Map.of("id", "1"));

        Map<String, String> out = XeapiCrypto.encrypt("/api/test", data, state, "", "", "android");

        // S：ephPub | iv | ciphertext+tag
        byte[] s = Base64.getDecoder().decode(out.get("S"));
        byte[] ephRaw = Arrays.copyOfRange(s, 0, 32);
        byte[] iv = Arrays.copyOfRange(s, 32, 44);
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(server.getPrivate());
        ka.doPhase(XeapiCrypto.x25519PublicKey(ephRaw), true);
        byte[] aesKey = XeapiCrypto.deriveAesKey(ka.generateSecret(), ephRaw);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        String[] sPlain = new String(gcm.doFinal(Arrays.copyOfRange(s, 44, s.length)), UTF_8).split("\\|", -1);
        assertEquals("android", sPlain[1]);
        assertEquals("SK", sPlain[2]);
        byte[] dynamicKey = Base64.getDecoder().decode(sPlain[0]);

        // C：动态密钥解密 → 逆 midTransform → 静态密钥解密
        byte[] c = Base64.getUrlDecoder().decode(out.get("C"));
        byte[] inner = XeapiCrypto.midTransformInverse(XeapiCrypto.aesEcb(Cipher.DECRYPT_MODE, dynamicKey, c));
        String plain = new String(XeapiCrypto.aesEcb(Cipher.DECRYPT_MODE, XeapiCrypto.STATIC_KEY, inner), UTF_8);
        assertEquals("{\"content\":\"id=1\",\"queryString\":\"e_r=true\"}", plain);

        // R
        byte[] r = Base64.getDecoder().decode(out.get("R"));
        assertEquals("7|", new String(XeapiCrypto.aesEcb(Cipher.DECRYPT_MODE, XeapiCrypto.STATIC_KEY, r), UTF_8));
    }

    @Test
    void responseDecrypt() {
        byte[] enc = XeapiCrypto.aesEcb(Cipher.ENCRYPT_MODE, XeapiCrypto.EAPI_KEY, "{\"code\":200}".getBytes(UTF_8));
        assertEquals("{\"code\":200}", XeapiCrypto.decryptResponse(enc));
    }

    /** 与 Node 上游对比：tools/gen-vectors.js 生成的 xeapi 段（随机数固定为 0，S 含临时密钥，无法固定，只比 C 和 R）。 */
    @Test
    void matchesUpstreamVectors() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("vectors.json");
        assumeTrue(in != null, "未找到 vectors.json，跳过对比测试");
        JsonNode root = new ObjectMapper().readTree(in);
        for (JsonNode v : root.path("xeapiSign")) {
            assertEquals(v.path("signature").asText(),
                XeapiCrypto.sign(v.path("timestamp").asText(), v.path("nonce").asText()), "xeapiSign");
        }
        for (JsonNode v : root.path("xeapi")) {
            Map<String, Object> data = new ObjectMapper().convertValue(v.path("data"), LinkedHashMap.class);
            var state = new XeapiCrypto.PublicKeyState(v.path("publicKey").asText(), v.path("version").asText(),
                v.path("sk").asText());
            Map<String, String> out = XeapiCrypto.encrypt(v.path("uri").asText(), data, state, "", "", "android", ZERO);
            assertEquals(v.path("C").asText(), out.get("C"), "xeapi C " + v.path("uri").asText());
            assertEquals(v.path("R").asText(), out.get("R"), "xeapi R");
        }
    }
}

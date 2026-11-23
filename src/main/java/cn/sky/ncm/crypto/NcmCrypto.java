package cn.sky.ncm.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对应上游 util/crypto.js 中的 weapi / linuxapi / eapi。
 * xeapi / neapi 在 M3 / M7 实现。
 */
public final class NcmCrypto {

    static final String IV = "0102030405060708";
    static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    static final String LINUXAPI_KEY = "rFgB&h#%2?^eDg:Q";
    static final String EAPI_KEY = "e82ckenh8dichen8";
    static final String BASE62 =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final String PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";

    private static final BigInteger RSA_MODULUS;
    private static final BigInteger RSA_EXPONENT;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    static {
        try {
            var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY));
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
            RSA_MODULUS = key.getModulus();
            RSA_EXPONENT = key.getPublicExponent();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private NcmCrypto() {
    }

    /** 与上游一致：base62.charAt(Math.round(Math.random() * 61)) × 16。 */
    public static String randomSecretKey() {
        var sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(BASE62.charAt((int) Math.round(RANDOM.nextDouble() * 61)));
        }
        return sb.toString();
    }

    public static Map<String, String> weapi(String json) {
        return weapi(json, randomSecretKey());
    }

    /** 可指定 secretKey，便于和 Node 版做对比测试。 */
    public static Map<String, String> weapi(String json, String secretKey) {
        String params = aesCbcBase64(aesCbcBase64(json, PRESET_KEY, IV), secretKey, IV);
        String reversed = new StringBuilder(secretKey).reverse().toString();
        var out = new LinkedHashMap<String, String>();
        out.put("params", params);
        out.put("encSecKey", rsaNoPadding(reversed));
        return out;
    }

    public static Map<String, String> linuxapi(String json) {
        return Map.of("eparams", aesEcbHex(json, LINUXAPI_KEY));
    }

    public static Map<String, String> eapi(String url, String text) {
        String digest = md5Hex("nobody" + url + "use" + text + "md5forencrypt");
        String data = url + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest;
        return Map.of("params", aesEcbHex(data, EAPI_KEY));
    }

    /** eapi 响应解密（e_r=true 时使用）；gzip=true 对应请求头 x-aeapi。 */
    public static String eapiResDecrypt(byte[] cipherBytes, boolean gzip) {
        byte[] plain = aesEcb(Cipher.DECRYPT_MODE, cipherBytes, EAPI_KEY);
        if (gzip) {
            plain = gunzip(plain);
        }
        return new String(plain, UTF_8);
    }

    /** eapi 请求解密（对应上游 eapiReqDecrypt，供调试工具使用）。 */
    public static String eapiReqDecrypt(String hexParams) {
        byte[] plain = aesEcb(Cipher.DECRYPT_MODE, HEX.parseHex(hexParams.toLowerCase()), EAPI_KEY);
        return new String(plain, UTF_8);
    }

    /** 对应 eapiReqDecrypt：返回 [url, dataJson]，格式不对时返回 null。 */
    public static String[] eapiReqDecryptParts(String hexParams) {
        String plain = eapiReqDecrypt(hexParams);
        var m = java.util.regex.Pattern.compile("(.*?)-36cd479b6b5-(.*?)-36cd479b6b5-(.*)", java.util.regex.Pattern.DOTALL)
            .matcher(plain);
        return m.find() ? new String[] {m.group(1), m.group(2)} : null;
    }

    /** eapi 响应解密（十六进制输入，对应 eapiResDecrypt(hex)）。 */
    public static String eapiResDecryptHex(String hex, boolean gzip) {
        return eapiResDecrypt(HEX.parseHex(hex.toLowerCase()), gzip);
    }

    /** linuxapi 请求解密（十六进制输入）。 */
    public static String linuxapiDecryptHex(String hex) {
        return new String(aesEcb(Cipher.DECRYPT_MODE, HEX.parseHex(hex.toLowerCase()), LINUXAPI_KEY), UTF_8);
    }

    static String aesCbcBase64(String text, String key, String iv) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(UTF_8)));
            return Base64.getEncoder().encodeToString(c.doFinal(text.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String aesEcbHex(String text, String key) {
        return HEX.formatHex(aesEcb(Cipher.ENCRYPT_MODE, text.getBytes(UTF_8), key)).toUpperCase();
    }

    static byte[] aesEcb(int mode, byte[] input, String key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(mode, new SecretKeySpec(key.getBytes(UTF_8), "AES"));
            return c.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对应 node-forge 的 encrypt(str, 'NONE')：无填充 RSA，输出补齐到模长的十六进制。 */
    static String rsaNoPadding(String text) {
        BigInteger m = new BigInteger(1, text.getBytes(UTF_8));
        String hex = m.modPow(RSA_EXPONENT, RSA_MODULUS).toString(16);
        int len = (RSA_MODULUS.bitLength() + 7) / 8 * 2;
        return "0".repeat(Math.max(0, len - hex.length())) + hex;
    }

    public static String md5Hex(String text) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gunzip(byte[] data) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

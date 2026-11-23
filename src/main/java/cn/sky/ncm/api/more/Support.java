package cn.sky.ncm.api.more;

import cn.sky.ncm.api.ExprEval;
import cn.sky.ncm.client.Js;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 手写接口共用的小工具，尽量贴近上游 JS 写法。 */
final class Support {

    static final SecureRandom RANDOM = new SecureRandom();

    private Support() {
    }

    /** 构造有序 Map；值为 null（JS 的 undefined）时跳过。 */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** JS 的 a || b：a 为 null 或空串时用 b。 */
    static Object or(String v, Object fallback) {
        return v != null && !v.isEmpty() ? v : fallback;
    }

    static String or(String v, String fallback) {
        return v != null && !v.isEmpty() ? v : fallback;
    }

    /** 解构默认值：只有 undefined（null）才用默认值。 */
    static Object def(String v, Object fallback) {
        return v != null ? v : fallback;
    }

    static boolean blank(String v) {
        return v == null || v.isEmpty();
    }

    /** JS 字符串拼接（undefined 拼成 "undefined"）。 */
    static String cat(Object... parts) {
        var sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(Js.string(p));
        }
        return sb.toString();
    }

    static String resourceType(String type) {
        return ExprEval.RESOURCE_TYPE_MAP.get(type == null ? "undefined" : type);
    }

    static String md5(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对应 JS 的 encodeURI。 */
    static String encodeURI(String s) {
        var sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || ";,/?:@&=+$-_.!~*'()#".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    static String randomChars(String alphabet, int len) {
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 安卓客户端身份（上游 level=vivid 时覆盖 os、appver）。 */
    static Map<String, String> asAndroid(Map<String, String> cookie) {
        Map<String, String> c = new LinkedHashMap<>(cookie == null ? Map.of() : cookie);
        c.put("os", "android");
        c.put("appver", "9.5.61");
        return c;
    }
}

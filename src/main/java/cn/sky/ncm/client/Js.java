package cn.sky.ncm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** 模拟 JS 的类型转换语义（String()、真值、Number()、JSON.stringify）。 */
public final class Js {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Js() {
    }

    /** 等价于 JS 的 String(v)；null 视为 undefined。 */
    public static String string(Object v) {
        if (v == null) {
            return "undefined";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double d) {
            if (d.isNaN()) {
                return "NaN";
            }
            if (d.isInfinite()) {
                return d > 0 ? "Infinity" : "-Infinity";
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e21) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        if (v instanceof Map<?, ?>) {
            return "[object Object]";
        }
        if (v instanceof Collection<?> c) {
            var j = new StringJoiner(",");
            c.forEach(x -> j.add(x == null ? "" : string(x)));
            return j.toString();
        }
        return String.valueOf(v);
    }

    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    public static double number(Object v) {
        if (v == null) {
            return Double.NaN;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return 0;
            }
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** 数字结果：整数用 Long，其他用 Double。 */
    public static Object numeric(double d) {
        if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 9e15) {
            return (long) d;
        }
        return d;
    }

    /** 等价于 JSON.stringify：Map 中值为 undefined(null) 的键省略，NaN/Infinity 写成 null。 */
    public static String json(Object v) {
        try {
            return MAPPER.writeValueAsString(normalize(v));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object normalize(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> {
                if (x != null) {
                    out.put(String.valueOf(k), normalize(x));
                }
            });
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            c.forEach(x -> out.add(normalize(x)));
            return out;
        }
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return null;
        }
        return v;
    }
}

package cn.sky.ncm.api;

import cn.sky.ncm.client.Js;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口描述里的 JS 表达式求值器。支持的语法（与 tools/convert 的校验器保持一致）：
 * <pre>
 * 三元 a ? b : c；逻辑 || ?? &amp;&amp; !；比较 == === != !==；算术 + - * /；一元负号
 * query.x、字符串、模板字符串 `..${expr}..`、数字、true/false/null/undefined
 * Date.now()、JSON.stringify()、parseInt()、Number()、String()、isNaN()
 * resourceTypeMap[expr]、对象字面量 {k: v}、数组字面量 [a, b]、括号
 * </pre>
 * null 表示 JS 的 undefined。
 */
public final class ExprEval {

    public static final Map<String, String> RESOURCE_TYPE_MAP = Map.of(
        "0", "R_SO_4_", "1", "R_MV_5_", "2", "A_PL_0_", "3", "R_AL_3_",
        "4", "A_DJ_1_", "5", "R_VI_62_", "6", "A_EV_2_", "7", "A_DR_14_");

    private ExprEval() {
    }

    public static Object eval(String expr, Map<String, Object> query) {
        var p = new Parser(expr, query);
        Object v = p.expr();
        p.ws();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("不支持的表达式: " + expr);
        }
        return v;
    }

    /** 模板字符串（不含反引号），${} 内可以是任意支持的表达式。 */
    public static String template(String tpl, Map<String, Object> query) {
        var p = new Parser("`" + tpl + "`", query);
        return (String) p.expr();
    }

    static Object parseInt(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? (Object) Double.NaN : (Object) (long) d;
        }
        Matcher m = Pattern.compile("^\\s*([+-]?\\d+)").matcher(Js.string(v));
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return Double.NaN;
    }

    private static final class Parser {
        final String s;
        final Map<String, Object> q;
        int pos;

        Parser(String s, Map<String, Object> q) {
            this.s = s;
            this.q = q;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean peek(String t) {
            ws();
            return s.startsWith(t, pos);
        }

        boolean eat(String t) {
            if (peek(t)) {
                pos += t.length();
                return true;
            }
            return false;
        }

        boolean eatWord(String w) {
            ws();
            if (s.startsWith(w, pos)) {
                int e = pos + w.length();
                if (e >= s.length() || !isIdent(s.charAt(e))) {
                    pos = e;
                    return true;
                }
            }
            return false;
        }

        void expect(String t) {
            if (!eat(t)) {
                throw new IllegalArgumentException("缺少 '" + t + "': " + s);
            }
        }

        static boolean isIdent(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        Object expr() {
            Object c = or();
            if (peek("?") && !peek("?.") && !peek("??")) {
                eat("?");
                Object a = expr();
                expect(":");
                Object b = expr();
                return Js.truthy(c) ? a : b;
            }
            return c;
        }

        Object or() {
            Object left = and();
            while (true) {
                if (eat("||")) {
                    Object right = and();
                    left = Js.truthy(left) ? left : right;
                } else if (eat("??")) {
                    Object right = and();
                    left = left != null ? left : right;
                } else {
                    return left;
                }
            }
        }

        Object and() {
            Object left = eq();
            while (eat("&&")) {
                Object right = eq();
                left = Js.truthy(left) ? right : left;
            }
            return left;
        }

        Object eq() {
            Object left = add();
            if (eat("===")) {
                return strictEq(left, add());
            }
            if (eat("!==")) {
                return !strictEq(left, add());
            }
            if (eat("==")) {
                return looseEq(left, add());
            }
            if (eat("!=")) {
                return !looseEq(left, add());
            }
            return left;
        }

        Object add() {
            Object left = mul();
            while (true) {
                ws();
                if (pos < s.length() && s.charAt(pos) == '+' && !s.startsWith("++", pos)) {
                    pos++;
                    Object right = mul();
                    if (left instanceof String || right instanceof String || left instanceof Map
                        || right instanceof Map || left instanceof List || right instanceof List) {
                        left = Js.string(left) + Js.string(right);
                    } else {
                        left = Js.numeric(Js.number(left) + Js.number(right));
                    }
                } else if (pos < s.length() && s.charAt(pos) == '-' && !s.startsWith("--", pos)) {
                    pos++;
                    Object right = mul();
                    left = Js.numeric(Js.number(left) - Js.number(right));
                } else {
                    return left;
                }
            }
        }

        Object mul() {
            Object left = unary();
            while (true) {
                ws();
                if (pos < s.length() && s.charAt(pos) == '*') {
                    pos++;
                    left = Js.numeric(Js.number(left) * Js.number(unary()));
                } else if (pos < s.length() && s.charAt(pos) == '/') {
                    pos++;
                    left = Js.numeric(Js.number(left) / Js.number(unary()));
                } else {
                    return left;
                }
            }
        }

        Object unary() {
            if (eat("!")) {
                return !Js.truthy(unary());
            }
            ws();
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
                return Js.numeric(-Js.number(unary()));
            }
            return primary();
        }

        Object primary() {
            ws();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("表达式不完整: " + s);
            }
            char c = s.charAt(pos);
            if (eat("query.")) {
                return q.get(ident());
            }
            if (c == '\'' || c == '"') {
                return string(c);
            }
            if (c == '`') {
                return template();
            }
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                    pos++;
                }
                String num = s.substring(start, pos);
                return num.contains(".") ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            }
            if (eatWord("true")) {
                return Boolean.TRUE;
            }
            if (eatWord("false")) {
                return Boolean.FALSE;
            }
            if (eatWord("null") || eatWord("undefined")) {
                return null;
            }
            if (eat("Date.now()")) {
                return System.currentTimeMillis();
            }
            if (eat("JSON.stringify(")) {
                Object v = expr();
                expect(")");
                return v == null ? null : Js.json(v);
            }
            if (eat("parseInt(")) {
                Object v = expr();
                if (eat(",")) {
                    expr();
                }
                expect(")");
                return parseInt(v);
            }
            if (eat("Number(")) {
                Object v = expr();
                expect(")");
                return Js.numeric(Js.number(v));
            }
            if (eat("String(")) {
                Object v = expr();
                expect(")");
                return Js.string(v);
            }
            if (eat("isNaN(")) {
                Object v = expr();
                expect(")");
                return Double.isNaN(Js.number(v));
            }
            if (eat("resourceTypeMap[")) {
                Object k = expr();
                expect("]");
                return RESOURCE_TYPE_MAP.get(Js.string(k));
            }
            if (eat("(")) {
                Object v = expr();
                expect(")");
                return v;
            }
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            throw new IllegalArgumentException("不支持的表达式: " + s.substring(pos));
        }

        String ident() {
            int start = pos;
            while (pos < s.length() && isIdent(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("缺少标识符: " + s);
            }
            return s.substring(start, pos);
        }

        String string(char quote) {
            var sb = new StringBuilder();
            pos++;
            while (pos < s.length() && s.charAt(pos) != quote) {
                char ch = s.charAt(pos++);
                if (ch == '\\' && pos < s.length()) {
                    ch = unescape(s.charAt(pos++));
                }
                sb.append(ch);
            }
            pos++;
            return sb.toString();
        }

        String template() {
            var sb = new StringBuilder();
            pos++;
            while (pos < s.length() && s.charAt(pos) != '`') {
                if (s.startsWith("${", pos)) {
                    pos += 2;
                    sb.append(Js.string(expr()));
                    expect("}");
                    continue;
                }
                char ch = s.charAt(pos++);
                if (ch == '\\' && pos < s.length()) {
                    ch = unescape(s.charAt(pos++));
                }
                sb.append(ch);
            }
            pos++;
            return sb.toString();
        }

        static char unescape(char c) {
            return switch (c) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                default -> c;
            };
        }

        String key() {
            ws();
            char c = s.charAt(pos);
            if (c == '\'' || c == '"') {
                return string(c);
            }
            return ident();
        }

        Map<String, Object> object() {
            expect("{");
            Map<String, Object> m = new LinkedHashMap<>();
            while (!peek("}")) {
                String k = key();
                expect(":");
                m.put(k, expr());
                if (!eat(",")) {
                    break;
                }
            }
            expect("}");
            return m;
        }

        List<Object> array() {
            expect("[");
            List<Object> list = new ArrayList<>();
            while (!peek("]")) {
                list.add(expr());
                if (!eat(",")) {
                    break;
                }
            }
            expect("]");
            return list;
        }

        static boolean strictEq(Object a, Object b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a instanceof Number x && b instanceof Number y) {
                return x.doubleValue() == y.doubleValue();
            }
            return a.getClass() == b.getClass() && Objects.equals(a, b);
        }

        static boolean looseEq(Object a, Object b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a instanceof String && b instanceof String) {
                return a.equals(b);
            }
            if (a instanceof Number || b instanceof Number || a instanceof Boolean || b instanceof Boolean) {
                return Js.number(a) == Js.number(b);
            }
            return Objects.equals(Js.string(a), Js.string(b));
        }
    }
}

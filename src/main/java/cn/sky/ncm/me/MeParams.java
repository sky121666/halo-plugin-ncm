package cn.sky.ncm.me;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebInputException;

/**
 * 个人音乐空间接口的参数校验。校验不通过时抛 {@link ServerWebInputException}（400）。
 * 所有 ID 都只允许 1~20 位数字，原样以字符串传给网易云，避免精度和注入问题。
 */
public final class MeParams {

    public static final Pattern ID = Pattern.compile("^\\d{1,20}$");
    /** likes/check 一次最多查询的歌曲数。 */
    public static final int MAX_CHECK_IDS = 200;
    /** 一次最多加入 / 移出歌单的歌曲数。 */
    public static final int MAX_TRACK_IDS = 100;
    /** 分页每页最多条数。 */
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 30;
    /** offset 上限，网易云的歌单最多一万首左右，这里留足余量。 */
    public static final int MAX_OFFSET = 100_000;
    /** 歌单名最多字数（按字符计，网易云 App 里的上限也是 40）。 */
    public static final int MAX_NAME = 40;
    /** 网易云的隐私歌单 privacy 取值。 */
    public static final int PRIVACY_PUBLIC = 0;
    public static final int PRIVACY_PRIVATE = 10;

    private MeParams() {
    }

    /** 单个 ID（路径变量）。 */
    public static String id(String raw, String field) {
        String v = raw == null ? "" : raw.trim();
        if (!ID.matcher(v).matches()) {
            throw new ServerWebInputException(field + " 应为 1~20 位数字");
        }
        return v;
    }

    /** 逗号分隔的 ID 列表：去掉空白和重复项，保留原顺序；为空或超过 max 个时报错。 */
    public static List<String> idList(String csv, String field, int max) {
        if (csv == null || csv.isBlank()) {
            throw new ServerWebInputException("缺少参数 " + field);
        }
        List<String> parts = new ArrayList<>();
        for (String s : csv.split(",", -1)) {
            if (!s.isBlank()) {
                parts.add(s);
            }
        }
        return idList(parts, field, max);
    }

    /** ID 列表（JSON 请求体）：规则同上。 */
    public static List<String> idList(Collection<String> raw, String field, int max) {
        if (raw == null || raw.isEmpty()) {
            throw new ServerWebInputException("缺少参数 " + field);
        }
        // 先粗略限制数量，避免超长列表做无用的校验
        if (raw.size() > max * 2) {
            throw new ServerWebInputException(field + " 最多 " + max + " 个");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            out.add(id(s, field));
        }
        if (out.size() > max) {
            throw new ServerWebInputException(field + " 最多 " + max + " 个");
        }
        return List.copyOf(out);
    }

    public static int offset(Optional<String> raw) {
        return intParam(raw, "offset", 0, 0, MAX_OFFSET);
    }

    public static int limit(Optional<String> raw) {
        return intParam(raw, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
    }

    static int intParam(Optional<String> raw, String field, int fallback, int min, int max) {
        String v = raw == null ? "" : raw.orElse("").trim();
        if (v.isEmpty()) {
            return fallback;
        }
        if (!v.matches("^\\d{1,9}$")) {
            throw new ServerWebInputException(field + " 应为 " + min + "~" + max + " 的整数");
        }
        int n = Integer.parseInt(v);
        if (n < min || n > max) {
            throw new ServerWebInputException(field + " 应为 " + min + "~" + max + " 的整数");
        }
        return n;
    }

    /** 歌单名：去掉首尾空白后 1~40 个字，不能含换行等控制字符。 */
    public static String playlistName(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) {
            throw new ServerWebInputException("歌单名不能为空");
        }
        if (v.codePointCount(0, v.length()) > MAX_NAME) {
            throw new ServerWebInputException("歌单名最多 " + MAX_NAME + " 个字");
        }
        if (v.codePoints().anyMatch(Character::isISOControl)) {
            throw new ServerWebInputException("歌单名不能包含换行等控制字符");
        }
        return v;
    }

    /** privacy：不填为公开（0），10 为隐私歌单，其他值报错。 */
    public static int privacy(Integer raw) {
        if (raw == null) {
            return PRIVACY_PUBLIC;
        }
        if (raw != PRIVACY_PUBLIC && raw != PRIVACY_PRIVATE) {
            throw new ServerWebInputException("privacy 只能是 0（公开）或 10（隐私）");
        }
        return raw;
    }

    /** 听歌排行类型：week（默认）或 all。 */
    public static String rankType(Optional<String> raw) {
        String v = raw == null ? "" : raw.orElse("").trim();
        if (v.isEmpty() || "week".equals(v)) {
            return "week";
        }
        if ("all".equals(v)) {
            return "all";
        }
        throw new ServerWebInputException("type 只能是 week 或 all");
    }
}

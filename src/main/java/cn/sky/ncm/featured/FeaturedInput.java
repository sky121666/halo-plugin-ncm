package cn.sky.ncm.featured;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别站长粘贴的内容：歌单 ID、网易云歌单链接、分享文案、163cn.tv 短链。纯文本处理，不发任何请求。
 * <p>
 * 短链需要服务端发一次请求才能知道指向哪里，这里只返回短链代码，由 {@link FeaturedRemote#expandShort} 用固定的
 * {@code https://163cn.tv/{代码}} 地址请求（不接受任何其他站点），再用 {@link #parseLocation} 校验跳转结果。
 */
public final class FeaturedInput {

    public enum Kind {
        /** 已识别出歌单 ID */
        ID,
        /** 163cn.tv 短链，需要服务端展开 */
        SHORT,
        /** 无法识别 */
        ERROR
    }

    /**
     * @param error 错误代码：empty / too_long / unrecognized / not_netease / unsupported_type / invalid_url /
     *              bad_short / redirect_loop / redirect_rejected
     */
    public record Parsed(Kind kind, String playlistId, String shortCode, String error, String message) {

        static Parsed id(String id) {
            return new Parsed(Kind.ID, id, null, null, null);
        }

        static Parsed shortLink(String code) {
            return new Parsed(Kind.SHORT, null, code, null, null);
        }

        static Parsed error(String error, String message) {
            return new Parsed(Kind.ERROR, null, null, error, message);
        }

        public boolean ok() {
            return kind == Kind.ID;
        }
    }

    public static final int MAX_INPUT = 2000;
    static final Pattern ID = Pattern.compile("^\\d{1,20}$");
    static final Pattern SHORT_CODE = Pattern.compile("^/([A-Za-z0-9]{4,16})/?$");
    /** 网易云音乐网页 / 手机网页的域名（只用来识别，不会请求它们） */
    static final Set<String> MUSIC_HOSTS = Set.of("music.163.com", "y.music.163.com", "m.music.163.com");
    /** 网易云的短链域名（服务端只会请求这一个域名） */
    static final Set<String> SHORT_HOSTS = Set.of("163cn.tv");
    static final String STOP = "\\s\"'<>()（）【】「」『』，。、；！？";
    static final Pattern URL = Pattern.compile("https?://[^" + STOP + "]+", Pattern.CASE_INSENSITIVE);
    static final Pattern BARE = Pattern.compile(
        // 前面紧挨着字母、数字、点、斜杠、冒号、@ 时不算（排除 ftp://music.163.com、evilmusic.163.com 这类）
        "(?<![\\w./:@-])(?:(?:y|m)\\.)?(?:music\\.163\\.com|163cn\\.tv)/[^" + STOP + "]*", Pattern.CASE_INSENSITIVE);
    static final Pattern TYPE = Pattern.compile(
        "(?:^|/)(playlist|album|song|artist|djradio|program|mv|video|user|dj|event)(?=[/?]|$)",
        Pattern.CASE_INSENSITIVE);
    static final Pattern PATH_ID = Pattern.compile("(?:^|/)playlist/(\\d{1,20})(?=[/?]|$)", Pattern.CASE_INSENSITIVE);
    static final Pattern ID_PARAM = Pattern.compile("(?:^|[?&])id=(\\d{1,20})(?=&|$)");
    static final Map<String, String> TYPE_LABEL = Map.of(
        "album", "专辑", "song", "单曲", "artist", "歌手", "djradio", "播客", "program", "播客节目",
        "mv", "MV", "video", "视频", "user", "用户主页", "dj", "播客", "event", "动态");

    private FeaturedInput() {
    }

    public static Parsed parse(String input) {
        String s = input == null ? "" : input.strip();
        if (s.isEmpty()) {
            return Parsed.error("empty", "请粘贴网易云歌单的分享链接、分享文案或歌单 ID");
        }
        if (s.length() > MAX_INPUT) {
            return Parsed.error("too_long", "内容太长了，请只粘贴分享链接或分享文案");
        }
        if (ID.matcher(s).matches()) {
            return validId(s) ? Parsed.id(trimZeros(s)) : Parsed.error("unrecognized", "歌单 ID 不正确");
        }
        List<String> urls = candidates(s);
        if (urls.isEmpty()) {
            return Parsed.error("unrecognized", "没有找到网易云歌单链接或歌单 ID");
        }
        Parsed firstError = null;
        for (String u : urls) {
            Parsed p = parseUrl(u);
            if (p.kind() != Kind.ERROR) {
                return p;
            }
            // 优先报告“是网易云链接但不是歌单”这类更具体的原因
            if (firstError == null || "not_netease".equals(firstError.error())) {
                firstError = p;
            }
        }
        return firstError;
    }

    /** 文本里的链接，按出现顺序；没有协议头的 music.163.com / 163cn.tv 补上 https。 */
    static List<String> candidates(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = URL.matcher(s);
        while (m.find() && out.size() < 10) {
            out.add(m.group());
        }
        if (out.isEmpty()) {
            Matcher b = BARE.matcher(s);
            while (b.find() && out.size() < 10) {
                out.add("https://" + b.group());
            }
        }
        return out;
    }

    /** 识别一个链接。只识别网易云的域名，其他一律拒绝。 */
    public static Parsed parseUrl(String url) {
        URI u;
        try {
            u = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return Parsed.error("invalid_url", "链接格式不正确");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty() || u.getRawUserInfo() != null) {
            return Parsed.error("not_netease", "不是网易云音乐的链接");
        }
        if (SHORT_HOSTS.contains(host)) {
            Matcher c = SHORT_CODE.matcher(u.getRawPath() == null ? "" : u.getRawPath());
            return c.matches() ? Parsed.shortLink(c.group(1)) : Parsed.error("bad_short", "短链格式不正确");
        }
        if (!MUSIC_HOSTS.contains(host)) {
            return Parsed.error("not_netease", "不是网易云音乐的链接");
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String query = u.getRawQuery() == null ? "" : u.getRawQuery();
        // https://music.163.com/#/playlist?id=123：类型和参数在 # 后面
        String fragment = u.getRawFragment() == null ? "" : u.getRawFragment();
        String fragPath = fragment;
        String fragQuery = "";
        int q = fragment.indexOf('?');
        if (q >= 0) {
            fragPath = fragment.substring(0, q);
            fragQuery = fragment.substring(q + 1);
        }
        String type = typeOf(fragPath);
        if (type == null) {
            type = typeOf(path);
        }
        if (type == null) {
            return Parsed.error("unrecognized", "没有从链接里找到歌单");
        }
        if (!"playlist".equals(type)) {
            return Parsed.error("unsupported_type",
                "这是" + TYPE_LABEL.getOrDefault(type, type) + "链接，目前只支持歌单");
        }
        String id = param(fragQuery);
        if (id == null) {
            id = param(query);
        }
        if (id == null) {
            Matcher pm = PATH_ID.matcher(fragPath);
            if (!pm.find()) {
                pm = PATH_ID.matcher(path);
                if (!pm.find()) {
                    return Parsed.error("unrecognized", "链接里没有歌单 ID");
                }
            }
            id = pm.group(1);
        }
        return validId(id) ? Parsed.id(trimZeros(id)) : Parsed.error("unrecognized", "链接里的歌单 ID 不正确");
    }

    /**
     * 校验短链的跳转地址（HTTP Location）：只接受指向网易云歌单页面的地址；再次跳到短链、跳到其他站点都拒绝。
     */
    public static Parsed parseLocation(String location) {
        if (location == null || location.isBlank()) {
            return Parsed.error("redirect_rejected", "短链没有跳转到歌单");
        }
        Parsed p = parseUrl(location);
        if (p.kind() == Kind.SHORT) {
            return Parsed.error("redirect_loop", "短链又跳到了短链，已拒绝");
        }
        if (p.kind() == Kind.ERROR && ("not_netease".equals(p.error()) || "invalid_url".equals(p.error()))) {
            return Parsed.error("redirect_rejected", "短链跳转到了网易云以外的地址，已拒绝");
        }
        return p;
    }

    static String typeOf(String path) {
        Matcher m = TYPE.matcher(path == null ? "" : path);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    static String param(String query) {
        Matcher m = ID_PARAM.matcher(query == null ? "" : query);
        return m.find() ? m.group(1) : null;
    }

    static String trimZeros(String id) {
        return id.replaceFirst("^0+(?=\\d)", "");
    }

    /** 歌单 ID 是否合法（1~20 位数字，不能是 0）。 */
    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches() && !"0".equals(trimZeros(id));
    }
}

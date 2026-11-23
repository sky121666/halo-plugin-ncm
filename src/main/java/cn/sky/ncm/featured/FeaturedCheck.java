package cn.sky.ncm.featured;

import cn.sky.ncm.api.more.ToolApis.SongUrl;
import cn.sky.ncm.client.NcmResponse;
import cn.sky.ncm.finder.FeaturedBridge;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SongVo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** 判断一个歌单能不能公开，以及按版权信息预估能听哪些歌。纯计算，不发请求。 */
public final class FeaturedCheck {

    public static final String OK = "ok";
    public static final String PRIVATE = "private";
    public static final String MISSING = "missing";
    public static final String ERROR = "error";
    public static final String UNCHECKED = "unchecked";

    /** 网易云 playlist.privacy：10 为私密歌单 */
    static final int PRIVACY_PRIVATE = 10;
    /** 网易云对不存在、已删除、无权访问的歌单返回的状态 */
    static final Set<Integer> DENIED = Set.of(401, 403, 404, 20001, 20002, 20005);
    public static final int MAX_COUNTED = 1000;

    /**
     * @param state    ok / private / missing / error
     * @param playlist 歌单信息（state 为 ok 或 private 时有）
     * @param message  给站长看的说明
     */
    public record Access(String state, PlaylistVo playlist, String message) {
        public boolean ok() {
            return OK.equals(state);
        }
    }

    private FeaturedCheck() {
    }

    /**
     * 用游客身份取到的 playlist_detail 判断能否公开；siteDetail 为站长账号取到的结果（没绑定或没取到时为 null），
     * 用来区分“已删除”和“私密 / 访客无权访问”。
     */
    public static Access access(NcmResponse guestDetail, NcmResponse siteDetail) {
        Access guest = single(guestDetail);
        if (!MISSING.equals(guest.state()) || siteDetail == null) {
            return guest;
        }
        Access site = single(siteDetail);
        if (site.playlist() != null) {
            return new Access(PRIVATE, site.playlist(), "歌单是私密的，或者访客没有权限访问，不能公开");
        }
        return guest;
    }

    static Access single(NcmResponse r) {
        if (r == null) {
            return new Access(ERROR, null, "暂时无法确认（没有拿到网易云的返回），请稍后重新核对");
        }
        JsonNode body = r.body();
        if (r.status() == 200 && FeaturedBridge.hasPlaylist(body)) {
            PlaylistVo p = FeaturedBridge.playlist(body);
            if (body.path("playlist").path("privacy").asInt(0) == PRIVACY_PRIVATE) {
                return new Access(PRIVATE, p, "这是私密歌单，访客看不到，不能公开");
            }
            return new Access(OK, p, null);
        }
        // 插件把网易云的部分业务码（400、502 等）也记成 HTTP 200，所以再看一下 body 里的 code
        int code = body == null ? r.status() : body.path("code").asInt(r.status());
        if (DENIED.contains(r.status()) || (r.status() == 200 && (code == 200 || code == 400 || DENIED.contains(code)))) {
            return new Access(MISSING, null, "歌单不存在、已被删除，或者访客没有权限访问");
        }
        return new Access(ERROR, null, "暂时无法确认（网易云返回 " + (code != 200 ? code : r.status()) + message(r)
            + "），请稍后重新核对");
    }

    static String message(NcmResponse r) {
        var b = r.bodyObject();
        String m = b.path("msg").asText(b.path("message").asText(""));
        return m.isBlank() ? "" : "：" + (m.length() > 60 ? m.substring(0, 60) : m);
    }

    /**
     * 从 playlist_track_all 的返回取歌曲。业务失败（HTTP 或 body.code 不是 200）、没有 songs 数组、
     * 歌单有曲目却返回空列表（空壳）时返回 null，调用方把全部曲目算作“待确认”。
     */
    public static List<SongVo> songsOf(NcmResponse r, int trackCount) {
        if (r == null || r.status() != 200) {
            return null;
        }
        JsonNode b = r.body();
        if (b == null || !b.isObject() || b.path("code").asInt(200) != 200 || !b.path("songs").isArray()) {
            return null;
        }
        List<SongVo> songs = FeaturedBridge.songs(b);
        if (songs.isEmpty() && trackCount > 0) {
            return null;
        }
        return songs;
    }

    /** 一首歌在某个身份下的预估：full / trial / unavailable / pending（没有版权信息，待确认）。 */
    public static String estimate(SongVo s) {
        String a = s == null ? null : s.getAvailability();
        return "full".equals(a) || "trial".equals(a) || "unavailable".equals(a) ? a : "pending";
    }

    /**
     * 按 playlist_track_all 的版权信息统计可播放情况。这是静态预估：完整 / 试听 / 不可播来自版权字段，
     * 没有版权信息的算“待确认”；访客实际能不能播以播放时的取址结果为准，页面不会据此禁用播放。
     *
     * @param songs      歌曲；为 null 表示请求失败，全部算待确认
     * @param trackCount 歌单总曲目数
     */
    public static NcmFeaturedPlaylist.Stats stats(List<SongVo> songs, int trackCount) {
        int full = 0;
        int trial = 0;
        int unavailable = 0;
        int pending = 0;
        List<SongVo> list = songs == null ? List.of() : songs;
        for (SongVo s : list) {
            switch (estimate(s)) {
                case "full" -> full++;
                case "trial" -> trial++;
                case "unavailable" -> unavailable++;
                default -> pending++;
            }
        }
        int total = Math.max(trackCount, list.size());
        int counted = list.size();
        var st = new NcmFeaturedPlaylist.Stats();
        st.setFull(full);
        st.setTrial(trial);
        st.setUnavailable(unavailable);
        // 请求失败时全部算待确认；超过 1000 首时没统计到的不算进任何一类，用 truncated 标出
        st.setPending(songs == null ? total : pending);
        st.setCounted(counted);
        st.setTotal(total);
        st.setTruncated(songs != null && total > counted && counted >= MAX_COUNTED);
        return st;
    }

    /**
     * 抽样实测：用游客身份真实取一次音频地址的结果，和播放器的判断一致。
     * 拿到地址时按 freeTrialInfo 区分完整和试听；网易云明确没有音源时为不可播；其他（出错、超时、限流）为待确认。
     */
    public static String measured(SongUrl songUrl) {
        return switch (songUrl.availability()) {
            case FULL -> "full";
            case TRIAL -> "trial";
            case UNAVAILABLE -> "unavailable";
            case RATE_LIMITED, SERVICE -> "pending";
        };
    }
}

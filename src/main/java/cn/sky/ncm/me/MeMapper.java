package cn.sky.ncm.me;

import cn.sky.ncm.endpoint.dto.NcmMePlaylist;
import cn.sky.ncm.endpoint.dto.NcmMePlaylistRecord;
import cn.sky.ncm.endpoint.dto.NcmMeRecord;
import cn.sky.ncm.endpoint.dto.NcmMeRef;
import cn.sky.ncm.endpoint.dto.NcmMeSong;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把网易云的原始 JSON 转成个人音乐空间的 DTO。字段缺失时给 null / 空列表，不抛异常。
 * （finder 包里的 NcmVoMapper 是包内可见的，这里按同样的规则单独写一份。）
 */
final class MeMapper {

    static final String SONG_PATH = "/music/song/";
    static final String PLAYLIST_PATH = "/music/playlist/";
    /** “我喜欢的音乐”歌单的 specialType。 */
    static final int LIKED_SPECIAL_TYPE = 5;

    private MeMapper() {
    }

    // ---------- 歌曲 ----------

    /** song_detail 的返回：{ songs, privileges }，按 ids 的顺序输出，网易云没返回的歌跳过。 */
    static List<NcmMeSong> songsInOrder(JsonNode body, List<String> ids) {
        Map<String, JsonNode> privileges = new HashMap<>();
        for (JsonNode p : body.path("privileges")) {
            privileges.put(p.path("id").asText(), p);
        }
        Map<String, NcmMeSong> byId = new HashMap<>();
        for (JsonNode s : body.path("songs")) {
            if (s.hasNonNull("id")) {
                NcmMeSong song = song(s, privileges.get(s.path("id").asText()));
                byId.put(song.getId(), song);
            }
        }
        List<NcmMeSong> out = new ArrayList<>();
        for (String id : ids) {
            NcmMeSong s = byId.get(id);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    static NcmMeSong song(JsonNode s, JsonNode privilege) {
        String id = s.path("id").asText();
        List<NcmMeRef> artists = new ArrayList<>();
        for (JsonNode a : first(s, "ar", "artists")) {
            artists.add(NcmMeRef.builder().id(idOf(a)).name(a.path("name").asText(null)).build());
        }
        JsonNode al = first(s, "al", "album");
        boolean hasAlbum = al.isObject() && (al.hasNonNull("id") || al.hasNonNull("name"));
        long dt = first(s, "dt", "duration").asLong(0);
        int fee = s.path("fee").asInt(0);
        JsonNode p = privilege != null ? privilege : s.get("privilege");
        Boolean playable = p == null || p.isNull() || p.isMissingNode() ? null
            : p.path("st").asInt(0) >= 0 && p.path("pl").asInt(0) > 0;
        return NcmMeSong.builder()
            .id(id)
            .name(s.path("name").asText(null))
            .artists(artists)
            .artistNames(artists.stream().map(NcmMeRef::getName).filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.joining(" / ")))
            .album(hasAlbum ? NcmMeRef.builder().id(idOf(al)).name(al.path("name").asText(null)).build() : null)
            .coverUrl(hasAlbum ? https(al.path("picUrl").asText(null)) : null)
            .durationMs(dt)
            .durationText(dt <= 0 ? "" : String.format("%d:%02d", dt / 60_000, (dt / 1000) % 60))
            .fee(fee)
            .vip(fee == 1)
            .playable(playable)
            .sitePath(SONG_PATH + id)
            .build();
    }

    // ---------- 歌单 ----------

    /** 单个歌单节点（user_playlist 的一项、playlist_detail 的 playlist、最近播放的 data）。 */
    static NcmMePlaylist playlist(JsonNode p, String myUid) {
        String id = idOf(p);
        String creatorId = creatorId(p);
        JsonNode c = p.path("creator");
        int trackCount = p.path("trackCount").asInt(-1);
        return NcmMePlaylist.builder()
            .id(id)
            .name(p.path("name").asText(null))
            .coverUrl(https(p.path("coverImgUrl").asText(p.path("coverUrl").asText(null))))
            .description(p.path("description").asText(null))
            .trackCount(trackCount >= 0 ? trackCount : null)
            .playCount(p.path("playCount").asLong(0))
            .own(myUid != null && myUid.equals(creatorId))
            .likedSongs(p.path("specialType").asInt(0) == LIKED_SPECIAL_TYPE)
            .privacy(p.path("privacy").asInt(0) == MeParams.PRIVACY_PRIVATE)
            .creatorId(creatorId)
            .creatorName(c.path("nickname").asText(null))
            .subscribed(p.has("subscribed") && !p.path("subscribed").isNull() ? p.path("subscribed").asBoolean() : null)
            .sitePath(PLAYLIST_PATH + id)
            .build();
    }

    /** 创建者 UID：优先 creator.userId，没有时用 userId。 */
    static String creatorId(JsonNode p) {
        JsonNode c = p.path("creator").path("userId");
        if (!c.isMissingNode() && !c.isNull() && !c.asText().isEmpty()) {
            return c.asText();
        }
        JsonNode u = p.path("userId");
        return u.isMissingNode() || u.isNull() || u.asText().isEmpty() ? null : u.asText();
    }

    /** user_playlist 的返回：{ playlist: [...] }。排序：“我喜欢的音乐” → 自己创建的 → 收藏的，组内保持原顺序。 */
    static List<NcmMePlaylist> playlists(JsonNode body, String myUid) {
        List<NcmMePlaylist> list = new ArrayList<>();
        for (JsonNode p : body.path("playlist")) {
            if (p.hasNonNull("id")) {
                list.add(playlist(p, myUid));
            }
        }
        list.sort(Comparator.comparingInt(MeMapper::playlistOrder));
        return list;
    }

    static int playlistOrder(NcmMePlaylist p) {
        boolean own = Boolean.TRUE.equals(p.getOwn());
        if (own && Boolean.TRUE.equals(p.getLikedSongs())) {
            return 0;
        }
        return own ? 1 : 2;
    }

    // ---------- 听歌记录 ----------

    /** record_recent_song 的返回：{ data: { list: [{ playTime, data: song }] } }。 */
    static List<NcmMeRecord> recentSongs(JsonNode body) {
        List<NcmMeRecord> list = new ArrayList<>();
        for (JsonNode r : body.path("data").path("list")) {
            JsonNode s = r.path("data").hasNonNull("id") ? r.path("data") : r.path("song");
            if (s.hasNonNull("id")) {
                list.add(NcmMeRecord.builder().song(song(s, null)).playedAt(time(r.path("playTime"))).build());
            }
        }
        return list;
    }

    /** record_recent_playlist 的返回：{ data: { list: [{ playTime, data: playlist }] } }。 */
    static List<NcmMePlaylistRecord> recentPlaylists(JsonNode body, String myUid) {
        List<NcmMePlaylistRecord> list = new ArrayList<>();
        for (JsonNode r : body.path("data").path("list")) {
            JsonNode p = r.path("data");
            if (p.hasNonNull("id")) {
                list.add(NcmMePlaylistRecord.builder().playlist(playlist(p, myUid))
                    .playedAt(time(r.path("playTime"))).build());
            }
        }
        return list;
    }

    /** user_record 的返回：{ weekData | allData: [{ playCount, score, song }] }。 */
    static List<NcmMeRecord> rank(JsonNode body, boolean week) {
        List<NcmMeRecord> list = new ArrayList<>();
        for (JsonNode r : body.path(week ? "weekData" : "allData")) {
            JsonNode s = r.path("song");
            if (s.hasNonNull("id")) {
                list.add(NcmMeRecord.builder()
                    .song(song(s, null))
                    .playCount(r.path("playCount").asInt(0))
                    .score(r.path("score").asInt(0))
                    .build());
            }
        }
        return list;
    }

    /** likelist 的返回：{ ids: [1, 2, ...] }。 */
    static List<String> likedIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode id : body.path("ids")) {
            String v = id.asText();
            if (MeParams.ID.matcher(v).matches()) {
                ids.add(v);
            }
        }
        return ids;
    }

    /** playlist_detail 的返回里的 trackIds。 */
    static List<String> trackIds(JsonNode playlist) {
        List<String> ids = new ArrayList<>();
        for (JsonNode t : playlist.path("trackIds")) {
            String v = t.path("id").asText();
            if (MeParams.ID.matcher(v).matches()) {
                ids.add(v);
            }
        }
        return ids;
    }

    // ---------- 工具 ----------

    static String time(JsonNode ms) {
        long t = ms.asLong(0);
        return t > 0 ? Instant.ofEpochMilli(t).toString() : null;
    }

    /** 网易云图片的 http 地址改成 https，避免 https 站点出现混合内容。 */
    static String https(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http://")) {
            try {
                String host = URI.create(url).getHost();
                if (host != null && (host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))) {
                    return "https://" + url.substring("http://".length());
                }
            } catch (IllegalArgumentException ignored) {
                return url;
            }
        }
        return url;
    }

    static JsonNode first(JsonNode n, String a, String b) {
        JsonNode x = n.get(a);
        return x != null && !x.isNull() ? x : n.path(b);
    }

    static String idOf(JsonNode n) {
        JsonNode id = n.get("id");
        return id == null || id.isNull() ? null : id.asText();
    }
}

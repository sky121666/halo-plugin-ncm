package cn.sky.ncm.finder;

import cn.sky.ncm.finder.vo.AlbumBriefVo;
import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.ArtistBriefVo;
import cn.sky.ncm.finder.vo.ArtistVo;
import cn.sky.ncm.finder.vo.HotKeywordVo;
import cn.sky.ncm.finder.vo.LyricVo;
import cn.sky.ncm.finder.vo.PlayRecordVo;
import cn.sky.ncm.finder.vo.PlaylistSummaryVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SearchResultVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.SuggestGroupVo;
import cn.sky.ncm.finder.vo.SuggestItemVo;
import cn.sky.ncm.finder.vo.SuggestVo;
import cn.sky.ncm.finder.vo.UserBriefVo;
import cn.sky.ncm.finder.vo.UserVo;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把网易云的原始 JSON 转成面向展示的 VO。字段缺失时尽量给出 null / 空列表，不抛异常，
 * 保证主题渲染不会因为网易云接口的小变化而失败。
 */
final class NcmVoMapper {

    static final java.time.ZoneId CN = java.time.ZoneId.of("Asia/Shanghai");
    /** 插件的播放地址（PlayerRouter），按请求者身份取音频：登录并绑定了网易云的访客用自己的账号。 */
    static final String PLAY_PATH = "/music/play/";
    static final Set<String> LEVELS =
        Set.of("standard", "exhigh", "lossless", "hires", "jyeffect", "vivid", "jymaster", "sky");

    private NcmVoMapper() {
    }

    // ---------- 地址 ----------

    /** 网易云图片 / 资源的 http 地址改成 https，避免 https 站点出现混合内容。 */
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

    /** 网易云图片缩略图：在地址后加 ?param=NyN。非网易云图片原样返回。 */
    static String thumbnail(String url, Integer size) {
        String u = https(url);
        if (u == null) {
            return "";
        }
        try {
            String host = URI.create(u).getHost();
            if (host == null || !host.endsWith(".music.126.net")) {
                return u;
            }
        } catch (IllegalArgumentException e) {
            return u;
        }
        int s = size == null ? 300 : Math.max(16, Math.min(size, 1024));
        int q = u.indexOf('?');
        return (q >= 0 ? u.substring(0, q) : u) + "?param=" + s + "y" + s;
    }

    static String playUrl(String id, String level) {
        String lv = level != null && LEVELS.contains(level) ? level : "standard";
        return PLAY_PATH + id + "?level=" + lv;
    }

    // ---------- 歌曲 ----------

    static Map<String, JsonNode> privileges(JsonNode body) {
        Map<String, JsonNode> map = new HashMap<>();
        for (JsonNode p : body.path("privileges")) {
            map.put(p.path("id").asText(), p);
        }
        return map;
    }

    /** song_detail / playlist_track_all 的返回：{ songs, privileges }。 */
    static List<SongVo> songs(JsonNode body) {
        return songList(body.path("songs"), privileges(body));
    }

    static List<SongVo> songList(JsonNode array, Map<String, JsonNode> privileges) {
        List<SongVo> list = new ArrayList<>();
        for (JsonNode s : array) {
            if (s.hasNonNull("id")) {
                list.add(song(s, privileges.get(s.path("id").asText())));
            }
        }
        return list;
    }

    static SongVo song(JsonNode s, JsonNode privilege) {
        String id = s.path("id").asText();
        JsonNode ar = first(s, "ar", "artists");
        JsonNode al = first(s, "al", "album");
        List<ArtistBriefVo> artists = new ArrayList<>();
        for (JsonNode a : ar) {
            artists.add(ArtistBriefVo.builder().id(idOf(a)).name(a.path("name").asText(null)).build());
        }
        String cover = https(al.path("picUrl").asText(null));
        long dt = first(s, "dt", "duration").asLong(0);
        int fee = s.path("fee").asInt(0);
        JsonNode p = privilege != null ? privilege : s.get("privilege");
        Boolean playable = p == null || p.isNull() ? null : p.path("st").asInt(0) >= 0 && p.path("pl").asInt(0) > 0;
        String availability = availability(p, fee);
        String mv = s.path("mv").asText(s.path("mvid").asText("0"));
        return SongVo.builder()
            .id(id)
            .name(s.path("name").asText(null))
            .aliases(texts(first(s, "alia", "alias")))
            .translations(texts(s.path("tns")))
            .artists(artists)
            .artistNames(artists.stream().map(ArtistBriefVo::getName).filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.joining(" / ")))
            .album(al.isMissingNode() || al.isNull() ? null
                : AlbumBriefVo.builder().id(idOf(al)).name(al.path("name").asText(null)).coverUrl(cover).build())
            .coverUrl(cover)
            .durationMs(dt)
            .durationText(dt <= 0 ? "" : String.format("%d:%02d", dt / 60_000, (dt / 1000) % 60))
            .fee(fee)
            .vip(fee == 1)
            .playable(playable)
            .availability(availability)
            .mvId(mv.isEmpty() || "0".equals(mv) ? null : mv)
            .url("https://music.163.com/#/song?id=" + id)
            .playUrl(playUrl(id, "standard"))
            .build();
    }

    /**
     * 按版权信息（privilege）预估可播放情况，只用于提示：
     * st &lt; 0 下架或没有版权 → unavailable；pl &gt; 0 → full；会员 / 付费专辑歌曲，或带试听权限 → trial；其他 → unavailable。
     */
    static String availability(JsonNode p, int songFee) {
        if (p == null || p.isNull() || p.isMissingNode()) {
            return null;
        }
        if (p.path("st").asInt(0) < 0) {
            return "unavailable";
        }
        if (p.path("pl").asInt(0) > 0) {
            return "full";
        }
        int fee = p.has("fee") ? p.path("fee").asInt(songFee) : songFee;
        JsonNode ftp = p.path("freeTrialPrivilege");
        boolean trialHint = ftp.isObject() && (ftp.path("resConsumable").asBoolean(false)
            || ftp.path("userConsumable").asBoolean(false) || ftp.has("listenType"));
        return fee == 1 || fee == 4 || trialHint ? "trial" : "unavailable";
    }

    // ---------- 歌单 / 专辑 / 歌手 ----------

    /** playlist_detail 的返回：{ playlist, privileges }；tracks 由调用方另外提供。 */
    static PlaylistVo playlist(JsonNode body, List<SongVo> tracks) {
        return playlistOf(body.path("playlist"), tracks);
    }

    /** 单个歌单节点（playlist_detail 的 playlist，或搜索结果里的一项）。 */
    static PlaylistVo playlistOf(JsonNode p, List<SongVo> tracks) {
        JsonNode c = p.path("creator");
        return PlaylistVo.builder()
            .id(idOf(p))
            .name(p.path("name").asText(null))
            .coverUrl(https(p.path("coverImgUrl").asText(null)))
            .description(p.path("description").asText(null))
            .tags(texts(p.path("tags")))
            .trackCount(p.path("trackCount").asInt(0))
            .playCount(p.path("playCount").asLong(0))
            .creator(c.isMissingNode() || c.isNull() ? null : UserBriefVo.builder()
                .userId(c.path("userId").asText(null))
                .nickname(c.path("nickname").asText(null))
                .avatarUrl(https(c.path("avatarUrl").asText(null)))
                .build())
            .tracks(tracks)
            .url("https://music.163.com/#/playlist?id=" + idOf(p))
            .build();
    }

    static boolean hasPlaylist(JsonNode body) {
        return body.path("playlist").hasNonNull("id");
    }

    /** user_playlist 的返回：{ playlist: [...] }。 */
    static List<PlaylistSummaryVo> playlistSummaries(JsonNode body, String ownerUid) {
        List<PlaylistSummaryVo> list = new ArrayList<>();
        for (JsonNode p : body.path("playlist")) {
            list.add(PlaylistSummaryVo.builder()
                .id(idOf(p))
                .name(p.path("name").asText(null))
                .coverUrl(https(p.path("coverImgUrl").asText(null)))
                .trackCount(p.path("trackCount").asInt(0))
                .playCount(p.path("playCount").asLong(0))
                .own(ownerUid != null && ownerUid.equals(p.path("creator").path("userId").asText(p.path("userId").asText())))
                .likedSongs(p.path("specialType").asInt(0) == 5)
                .url("https://music.163.com/#/playlist?id=" + idOf(p))
                .build());
        }
        return list;
    }

    /** album 的返回：{ album, songs }。 */
    static AlbumVo album(JsonNode body) {
        return albumOf(body.path("album"), songList(body.path("songs"), privileges(body)));
    }

    /** 单个专辑节点（album 接口的 album，或搜索结果里的一项）。 */
    static AlbumVo albumOf(JsonNode a, List<SongVo> tracks) {
        List<ArtistBriefVo> artists = new ArrayList<>();
        JsonNode ars = a.path("artists");
        if (ars.isArray() && !ars.isEmpty()) {
            ars.forEach(x -> artists.add(ArtistBriefVo.builder().id(idOf(x)).name(x.path("name").asText(null)).build()));
        } else if (a.path("artist").hasNonNull("id")) {
            JsonNode x = a.path("artist");
            artists.add(ArtistBriefVo.builder().id(idOf(x)).name(x.path("name").asText(null)).build());
        }
        long pt = a.path("publishTime").asLong(0);
        return AlbumVo.builder()
            .id(idOf(a))
            .name(a.path("name").asText(null))
            .coverUrl(https(a.path("picUrl").asText(null)))
            .description(a.path("description").asText(null))
            .company(a.path("company").asText(null))
            .publishTime(pt > 0 ? Instant.ofEpochMilli(pt) : null)
            .publishDate(pt > 0 ? Instant.ofEpochMilli(pt).atZone(CN).toLocalDate().toString() : null)
            .artists(artists)
            .artistNames(artists.stream().map(ArtistBriefVo::getName).filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.joining(" / ")))
            .size(a.path("size").asInt(0))
            .tracks(tracks)
            .url("https://music.163.com/#/album?id=" + idOf(a))
            .build();
    }

    static boolean hasAlbum(JsonNode body) {
        return body.path("album").hasNonNull("id");
    }

    /** artists 的返回：{ artist, hotSongs }。 */
    static ArtistVo artist(JsonNode body, int limit) {
        List<SongVo> hot = songList(body.path("hotSongs"), Map.of());
        return artistOf(body.path("artist"), hot.subList(0, Math.min(limit, hot.size())));
    }

    /** 单个歌手节点（artists 接口的 artist，或搜索结果里的一项）。 */
    static ArtistVo artistOf(JsonNode a, List<SongVo> hot) {
        List<String> aliases = texts(a.path("alias"));
        return ArtistVo.builder()
            .id(idOf(a))
            .name(a.path("name").asText(null))
            .aliases(aliases.isEmpty() ? texts(a.path("transNames")) : aliases)
            .coverUrl(https(a.path("picUrl").asText(a.path("cover").asText(a.path("img1v1Url").asText(null)))))
            .briefDesc(a.path("briefDesc").asText(null))
            .albumSize(a.path("albumSize").asInt(0))
            .musicSize(a.path("musicSize").asInt(0))
            .mvSize(a.path("mvSize").asInt(0))
            .hotSongs(hot)
            .url("https://music.163.com/#/artist?id=" + idOf(a))
            .build();
    }

    static boolean hasArtist(JsonNode body) {
        return body.path("artist").hasNonNull("id");
    }

    // ---------- 搜索 ----------

    /** cloudsearch 的返回：{ result: { songs / playlists / albums / artists, songCount / ... } }。 */
    static SearchResultVo search(JsonNode body, String keyword, SearchType type, int page, int size) {
        JsonNode r = body.path("result");
        JsonNode list = r.path(type.listField());
        List<SongVo> songs = new ArrayList<>();
        List<PlaylistVo> playlists = new ArrayList<>();
        List<AlbumVo> albums = new ArrayList<>();
        List<ArtistVo> artists = new ArrayList<>();
        for (JsonNode n : list) {
            if (!n.hasNonNull("id")) {
                continue;
            }
            switch (type) {
                case SONG -> songs.add(song(n, null));
                case PLAYLIST -> playlists.add(playlistOf(n, List.of()));
                case ALBUM -> albums.add(albumOf(n, List.of()));
                case ARTIST -> artists.add(artistOf(n, List.of()));
            }
        }
        int got = songs.size() + playlists.size() + albums.size() + artists.size();
        long total = Math.max(r.path(type.countField()).asLong(0), (long) (page - 1) * size + got);
        return SearchResultVo.builder()
            .keyword(keyword)
            .type(type.key())
            .page(page)
            .size(size)
            .total(total)
            .songs(songs)
            .playlists(playlists)
            .albums(albums)
            .artists(artists)
            .build();
    }

    static SearchResultVo emptySearch(String keyword, SearchType type, int page, int size) {
        return SearchResultVo.builder().keyword(keyword).type(type.key()).page(page).size(size).total(0)
            .songs(List.of()).playlists(List.of()).albums(List.of()).artists(List.of()).build();
    }

    /** 联想里各类型在网易云返回中的字段、类型 key、名称和站内路径前缀。 */
    record SuggestKind(String field, String type, String label, String pathPrefix) {
    }

    static final List<SuggestKind> SUGGEST_KINDS = List.of(
        new SuggestKind("songs", "song", "单曲", "/music/song/"),
        new SuggestKind("artists", "artist", "歌手", "/music/artist/"),
        new SuggestKind("albums", "album", "专辑", "/music/album/"),
        new SuggestKind("playlists", "playlist", "歌单", "/music/playlist/"));

    /**
     * search_suggest（web）的返回：{ result: { songs, artists, albums, playlists, order: ["songs", ...] } }。
     * 每组最多 perGroup 项，没有 id 的项和空组会去掉。
     */
    static SuggestVo suggest(JsonNode body, String keyword, int perGroup) {
        JsonNode r = body.path("result");
        List<String> order = new ArrayList<>(texts(r.path("order")));
        for (SuggestKind k : SUGGEST_KINDS) {
            if (!order.contains(k.field())) {
                order.add(k.field());
            }
        }
        List<SuggestGroupVo> groups = new ArrayList<>();
        for (String field : order) {
            SuggestKind kind = SUGGEST_KINDS.stream().filter(k -> k.field().equals(field)).findFirst().orElse(null);
            if (kind == null) {
                continue;
            }
            List<SuggestItemVo> items = new ArrayList<>();
            for (JsonNode n : r.path(field)) {
                String id = idOf(n);
                if (id == null || !id.matches("\\d{1,20}")) {
                    continue;
                }
                items.add(suggestItem(kind, id, n));
                if (items.size() >= perGroup) {
                    break;
                }
            }
            if (!items.isEmpty()) {
                groups.add(SuggestGroupVo.builder().type(kind.type()).label(kind.label()).items(items).build());
            }
        }
        return SuggestVo.builder().keyword(keyword).groups(groups).build();
    }

    static SuggestItemVo suggestItem(SuggestKind kind, String id, JsonNode n) {
        String name = n.path("name").asText("");
        String sub;
        String cover;
        switch (kind.type()) {
            case "song" -> {
                SongVo s = song(n, null);
                String album = s.getAlbum() == null ? null : s.getAlbum().getName();
                sub = join(" · ", s.getArtistNames(), album);
                cover = s.getCoverUrl();
            }
            case "album" -> {
                AlbumVo a = albumOf(n, List.of());
                sub = blankToNull(a.getArtistNames());
                cover = a.getCoverUrl();
            }
            case "playlist" -> {
                int count = n.path("trackCount").asInt(0);
                sub = count > 0 ? count + " 首" : null;
                cover = https(n.path("coverImgUrl").asText(null));
            }
            default -> {
                List<String> alias = texts(n.path("alias"));
                sub = alias.isEmpty() ? null : alias.get(0);
                cover = https(n.path("picUrl").asText(n.path("img1v1Url").asText(null)));
            }
        }
        return SuggestItemVo.builder()
            .type(kind.type())
            .id(id)
            .name(name)
            .sub(sub)
            .coverUrl(cover)
            .path(kind.pathPrefix() + id)
            .build();
    }

    static String join(String sep, String... parts) {
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                list.add(p.strip());
            }
        }
        return list.isEmpty() ? null : String.join(sep, list);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** search_hot_detail 的返回：{ data: [{ searchWord, content, score, iconUrl }] }。 */
    static List<HotKeywordVo> hotKeywords(JsonNode body, int limit) {
        List<HotKeywordVo> out = new ArrayList<>();
        for (JsonNode n : body.path("data")) {
            String word = n.path("searchWord").asText("").strip();
            if (word.isEmpty()) {
                continue;
            }
            String icon = n.path("iconUrl").asText("");
            String desc = n.path("content").asText("");
            out.add(HotKeywordVo.builder()
                .keyword(word)
                .description(desc.isBlank() ? null : desc.strip())
                .score(n.path("score").asLong(0))
                .iconUrl(icon.isBlank() ? null : https(icon))
                .build());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    // ---------- 歌词 / 用户 / 记录 ----------

    /** 同时保留逐行数据和 Folia 所需的原始歌词格式。 */
    static LyricVo lyric(String songId, JsonNode body) {
        Map<String, String> formats = new HashMap<>();
        for (String field : List.of("lrc", "tlyric", "yrc", "ytlrc", "romalrc", "yromalrc")) {
            String value = body.path(field).path("lyric").asText(null);
            if (value == null || value.isBlank()) {
                value = body.path("lrc").path(field).path("lyric").asText(null);
            }
            if (value != null && !value.isBlank()) {
                formats.put(field, value);
            }
        }
        String lrc = formats.get("lrc");
        String tlrc = formats.get("tlyric");
        var lines = LrcParser.merge(lrc, tlrc);
        boolean hasTr = lines.stream().anyMatch(l -> l.getTranslation() != null);
        boolean instrumental = body.path("pureMusic").asBoolean(false) || body.path("nolyric").asBoolean(false)
            || (lines.isEmpty() && !formats.containsKey("yrc"));
        return LyricVo.builder()
            .songId(songId)
            .lines(lines)
            .lyric(lrc)
            .translatedLyric(hasTr ? tlrc : null)
            .formats(Map.copyOf(formats))
            .hasTranslation(hasTr)
            .instrumental(instrumental)
            .build();
    }

    static List<Map<String, Long>> chorus(JsonNode body) {
        JsonNode ranges = body.path("chorus").isArray() ? body.path("chorus") : body.path("data");
        List<Map<String, Long>> result = new ArrayList<>();
        for (JsonNode range : ranges) {
            long start = range.path("startTime").asLong(-1);
            long end = range.path("endTime").asLong(-1);
            if (start >= 0 && end > start) {
                result.add(Map.of("startTime", start, "endTime", end));
            }
        }
        return result;
    }

    /** user_detail 的返回：{ level, listenSongs, profile }。 */
    static UserVo user(JsonNode body) {
        JsonNode p = body.path("profile");
        String uid = p.path("userId").asText(null);
        return UserVo.builder()
            .userId(uid)
            .nickname(p.path("nickname").asText(null))
            .avatarUrl(https(p.path("avatarUrl").asText(null)))
            .backgroundUrl(https(p.path("backgroundUrl").asText(null)))
            .signature(p.path("signature").asText(null))
            .level(body.path("level").asInt(0))
            .listenSongs(body.path("listenSongs").asInt(0))
            .vip(p.path("vipType").asInt(0) > 0)
            .follows(p.path("follows").asInt(0))
            .followeds(p.path("followeds").asInt(0))
            .playlistCount(p.path("playlistCount").asInt(0))
            .url(uid == null ? null : "https://music.163.com/#/user/home?id=" + uid)
            .build();
    }

    static boolean hasUser(JsonNode body) {
        return body.path("profile").hasNonNull("userId");
    }

    /** user_record 的返回：{ weekData | allData: [{ playCount, score, song }] }。 */
    static List<PlayRecordVo> rank(JsonNode body, boolean week, int limit) {
        List<PlayRecordVo> list = new ArrayList<>();
        for (JsonNode r : body.path(week ? "weekData" : "allData")) {
            if (list.size() >= limit) {
                break;
            }
            JsonNode s = r.path("song");
            if (s.hasNonNull("id")) {
                list.add(PlayRecordVo.builder()
                    .song(song(s, null))
                    .playCount(r.path("playCount").asInt(0))
                    .score(r.path("score").asInt(0))
                    .build());
            }
        }
        return list;
    }

    // ---------- 工具 ----------

    static JsonNode first(JsonNode n, String a, String b) {
        JsonNode x = n.get(a);
        return x != null && !x.isNull() ? x : n.path(b);
    }

    static String idOf(JsonNode n) {
        JsonNode id = n.get("id");
        return id == null || id.isNull() ? null : id.asText();
    }

    static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> {
                if (x.isTextual() && !x.asText().isBlank()) {
                    out.add(x.asText());
                }
            });
        }
        return out;
    }
}

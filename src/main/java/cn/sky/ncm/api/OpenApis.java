package cn.sky.ncm.api;

import java.util.Set;

/**
 * 默认对外开放（可以通过 /apis/api.ncm.halo.run/v1alpha1/public|personal/{name} 调用）的接口：
 * 主题和前端常用的只读接口——搜索、歌曲、歌词、歌单、专辑、歌手、排行榜、MV、评论，以及本人的账号、喜欢、听歌记录、推荐。
 * <p>
 * 插件移植了 450 多个接口，大部分和博客无关（一起听、云贝任务、音乐人任务……），全部对外开放会扩大风险面，
 * 默认开放这些只读接口。写入接口可在「接口管理 → 接口开放」中配置。
 * 插件自己的页面、播放器、后台和个人中心不经过这个限制。
 */
public final class OpenApis {

    public static final Set<String> COMMON = Set.of(
        "cloudsearch", "search", "search_suggest", "search_hot", "search_hot_detail", "search_default",
        "search_multimatch", "song_detail", "song_url", "song_url_v1", "song_url_v1_302", "check_music", "lyric",
        "lyric_new", "song_wiki_summary", "simi_song", "simi_playlist", "playlist_detail", "playlist_track_all",
        "playlist_detail_dynamic", "related_playlist", "top_playlist", "top_playlist_highquality",
        "playlist_catlist", "playlist_hot", "personalized", "personalized_newsong", "album", "album_detail",
        "album_detail_dynamic", "album_new", "album_newest", "top_album", "artists", "artist_detail",
        "artist_songs", "artist_top_song", "artist_album", "artist_desc", "artist_mv", "artist_list",
        "top_artists", "simi_artist", "toplist", "toplist_detail", "toplist_artist", "top_list", "top_song",
        "user_detail", "user_playlist", "user_record", "user_level", "mv_detail", "mv_url", "mv_all", "mv_first",
        "top_mv", "comment_music", "comment_hot", "comment_playlist", "comment_album", "comment_new", "banner",
        "dj_program", "dj_detail", "dj_hot", "user_account", "likelist", "record_recent_song",
        "record_recent_playlist", "recommend_songs", "recommend_resource", "personal_fm", "user_subcount",
        "song_like_check");

    private OpenApis() {
    }
}

package cn.sky.ncm.finder;

import cn.sky.ncm.finder.vo.AlbumVo;
import cn.sky.ncm.finder.vo.ArtistVo;
import cn.sky.ncm.finder.vo.HotKeywordVo;
import cn.sky.ncm.finder.vo.LyricVo;
import cn.sky.ncm.finder.vo.PlayRecordVo;
import cn.sky.ncm.finder.vo.PlaylistSummaryVo;
import cn.sky.ncm.finder.vo.PlaylistVo;
import cn.sky.ncm.finder.vo.SearchResultVo;
import cn.sky.ncm.finder.vo.SongVo;
import cn.sky.ncm.finder.vo.SuggestVo;
import cn.sky.ncm.finder.vo.UserVo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 主题模板里使用的网易云数据，变量名 {@code ncmFinder}。
 * <p>
 * 约定（属于插件和主题之间的版本化契约，自插件 0.1.0 起提供）：
 * <ul>
 *   <li>ID 一律以字符串传入，例如 {@code ncmFinder.song('186016')}（网易云的 ID 可能超过 int 范围）</li>
 *   <li>参数无效、网易云出错、超时（6 秒）时：Mono 方法返回 null，Flux 方法返回空列表，不会让页面报错</li>
 *   <li>limit 默认值见各方法，最大 200</li>
 *   <li>查询缓存可在「网络与限流」调整，默认 600 秒。请避免在 th:each 里逐条查询。</li>
 *   <li>公开数据使用「接口开放」设置的网易云身份。site 开头的方法需要站长已绑定账号，
 *       并在「网抑云 → 音乐首页 → 探索」中启用相应内容。</li>
 * </ul>
 */
public interface NcmFinder {

    // ---------- 公开数据 ----------

    /** 单首歌曲。 */
    Mono<SongVo> song(String id);

    /** 多首歌曲，ids 用英文逗号分隔，按传入顺序返回，最多 200 首。 */
    Flux<SongVo> songs(String ids);

    /** 歌单详情和前 50 首歌。 */
    Mono<PlaylistVo> playlist(String id);

    /** 歌单详情和前 limit 首歌；limit 为 0 时只返回歌单信息。 */
    Mono<PlaylistVo> playlist(String id, Integer limit);

    /** 歌单里从 offset 开始的 limit 首歌（limit 默认 50），用于分页。 */
    Flux<SongVo> playlistTracks(String id, Integer offset, Integer limit);

    /**
     * 搜索，type 为 song（默认）/ playlist / album / artist，page 从 1 开始，size 默认 30（最多 100）。
     * 关键词为空或超过 100 个字符时返回 null；最多翻到第 1000 条结果。
     */
    Mono<SearchResultVo> search(String keyword, String type, Integer page, Integer size);

    /** 搜索联想：单曲、歌手、专辑、歌单各最多 5 项。关键词无效时返回 null。 */
    Mono<SuggestVo> suggest(String keyword);

    /** 热搜词，limit 默认 10（最多 30）。 */
    Flux<HotKeywordVo> hotKeywords(Integer limit);

    /** 专辑详情和全部歌曲。 */
    Mono<AlbumVo> album(String id);

    /** 歌手详情和前 20 首热门歌曲。 */
    Mono<ArtistVo> artist(String id);

    /** 歌手详情和前 limit 首热门歌曲。 */
    Mono<ArtistVo> artist(String id, Integer limit);

    /** 歌词（已解析成行并合并译文）。 */
    Mono<LyricVo> lyric(String id);

    /** 插件提供的播放地址（标准音质，302 跳转到音频），可直接用作 audio 的 src；id 无效时为空串。 */
    String playUrl(String id);

    /** 指定音质的播放地址：standard / exhigh / lossless / hires / jyeffect / vivid / jymaster / sky，其他值按 standard。 */
    String playUrl(String id, String level);

    /** 网易云图片的缩略图地址（size 为边长像素，16~1024）；非网易云图片原样返回，空地址返回空串。 */
    String thumbnail(String url, Integer size);

    // ---------- 站长数据（需要绑定站长账号并在设置里打开） ----------

    /** 站长的网易云资料。设置项：siteProfile（默认打开）。 */
    Mono<UserVo> siteProfile();

    /** 站长的歌单（创建和收藏），前 30 个。设置项：sitePlaylists（默认打开）。 */
    Flux<PlaylistSummaryVo> sitePlaylists();

    /** 站长的前 limit 个歌单。 */
    Flux<PlaylistSummaryVo> sitePlaylists(Integer limit);

    /** 旧模板兼容，统一返回空列表。个人听歌历史通过个人曲库读取。 */
    @Deprecated
    default Flux<PlayRecordVo> siteRecentSongs() {
        return Flux.empty();
    }

    /** 旧模板兼容，统一返回空列表。 */
    @Deprecated
    default Flux<PlayRecordVo> siteRecentSongs(Integer limit) {
        return Flux.empty();
    }

    /** 站长允许游客查看的最近一周听歌排行前 10。设置项：siteRank（默认关闭）。 */
    Flux<PlayRecordVo> siteWeekRank();

    /** 站长最近一周听歌排行前 limit。 */
    Flux<PlayRecordVo> siteWeekRank(Integer limit);

    /** 站长全部时间听歌排行前 10。设置项：siteRank（默认关闭）。 */
    Flux<PlayRecordVo> siteAllRank();

    /** 站长全部时间听歌排行前 limit。 */
    Flux<PlayRecordVo> siteAllRank(Integer limit);

    /** 旧模板兼容，统一返回空列表。个性化推荐通过个人曲库读取。 */
    @Deprecated
    default Flux<SongVo> siteDailyRecommend() {
        return Flux.empty();
    }

    /** 旧模板兼容，统一返回空列表。 */
    @Deprecated
    default Flux<SongVo> siteDailyRecommend(Integer limit) {
        return Flux.empty();
    }
}

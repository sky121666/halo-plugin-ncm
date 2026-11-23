package cn.sky.ncm.finder.vo;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 专辑详情 */
@Value
@Builder
public class AlbumVo {

    /** 专辑 ID */
    String id;

    /** 专辑名 */
    String name;

    /** 封面地址（https） */
    String coverUrl;

    /** 简介 */
    String description;

    /** 发行公司 */
    String company;

    /** 发行时间 */
    Instant publishTime;

    /** 发行日期，例如 2003-07-31（按北京时间） */
    String publishDate;

    /** 歌手 */
    List<ArtistBriefVo> artists;

    /** 歌手名，用“ / ”连接 */
    String artistNames;

    /** 歌曲数 */
    Integer size;

    /** 歌曲 */
    List<SongVo> tracks;

    /** 网易云网页地址 */
    String url;
}

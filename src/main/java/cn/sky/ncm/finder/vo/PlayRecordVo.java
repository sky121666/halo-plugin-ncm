package cn.sky.ncm.finder.vo;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/** 听歌记录（最近播放或排行） */
@Value
@Builder
public class PlayRecordVo {

    /** 歌曲 */
    SongVo song;

    /** 播放次数（排行有值） */
    Integer playCount;

    /** 排行得分（排行有值） */
    Integer score;

    /** 最近播放时间（最近播放有值） */
    Instant playedAt;
}

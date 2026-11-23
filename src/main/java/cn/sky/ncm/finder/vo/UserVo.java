package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 网易云用户资料 */
@Value
@Builder
public class UserVo {

    /** 网易云 UID */
    String userId;

    /** 昵称 */
    String nickname;

    /** 头像地址（https） */
    String avatarUrl;

    /** 背景图地址（https） */
    String backgroundUrl;

    /** 签名 */
    String signature;

    /** 等级 */
    Integer level;

    /** 累计听歌数 */
    Integer listenSongs;

    /** 是否会员 */
    Boolean vip;

    /** 关注数 */
    Integer follows;

    /** 粉丝数 */
    Integer followeds;

    /** 歌单数 */
    Integer playlistCount;

    /** 网易云网页地址 */
    String url;
}

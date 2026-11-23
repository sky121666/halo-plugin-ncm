package cn.sky.ncm.finder.vo;

import lombok.Builder;
import lombok.Value;

/** 网易云用户（简要） */
@Value
@Builder
public class UserBriefVo {

    /** 网易云 UID */
    String userId;

    /** 昵称 */
    String nickname;

    /** 头像地址（https） */
    String avatarUrl;
}

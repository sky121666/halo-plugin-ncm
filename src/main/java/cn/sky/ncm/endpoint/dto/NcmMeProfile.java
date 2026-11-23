package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 个人音乐空间：绑定的网易云账号概况 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "个人音乐空间：绑定的网易云账号概况")
public class NcmMeProfile {

    @Schema(description = "网易云 UID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatarUrl;

    @Schema(description = "个性签名")
    private String signature;

    @Schema(description = "会员类型，0 为非会员")
    private Integer vipType;

    @Schema(description = "等级")
    private Integer level;

    @Schema(description = "累计听歌数")
    private Integer listenSongs;

    @Schema(description = "true 表示没有单独绑定、用的是本人在后台绑定的站长账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean viaSiteAccount;
}

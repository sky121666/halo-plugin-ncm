package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 绑定的网易云账号（不含 Cookie） */
@Data
@Schema(description = "绑定的网易云账号（不含 Cookie）")
public class NcmAccountView {

    @Schema(description = "是否已绑定", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean bound;

    @Schema(description = "网易云 UID")
    private Long userId;

    @Schema(description = "网易云昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatarUrl;

    @Schema(description = "会员类型，0 为非会员")
    private Integer vipType;

    @Schema(description = "绑定时间（ISO 8601）")
    private String boundAt;

    @Schema(description = "已保存的登录信息能否读取")
    private Boolean readable;

    @Schema(description = "个人中心显示本人在后台绑定的站长账号时为 true")
    private Boolean managedInConsole;
}

package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 登录或发验证码的结果 */
@Data
@Schema(description = "登录或发验证码的结果")
public class NcmLoginResult {

    @Schema(description = "800 过期 / 801 等待扫码 / 802 App 待确认 / 803 等待网页确认 / 200 操作成功",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer code;

    @Schema(description = "网易云返回的提示")
    private String message;

    @Schema(description = "登录成功后保存的账号")
    private NcmAccountView account;
}

package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 手机号登录，captcha 和 password 二选一 */
@Data
@Schema(description = "手机号登录，captcha 和 password 二选一")
public class NcmCellphoneLoginRequest {

    @Schema(description = "手机号（5~15 位数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Schema(description = "国家/地区代码，默认 86")
    private String countrycode;

    @Schema(description = "短信验证码（4~8 位数字）")
    private String captcha;

    @Schema(description = "网易云密码（服务端做 MD5 后发送，不保存）")
    private String password;
}

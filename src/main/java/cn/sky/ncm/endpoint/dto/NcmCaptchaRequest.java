package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 发送短信验证码 */
@Data
@Schema(description = "发送短信验证码")
public class NcmCaptchaRequest {

    @Schema(description = "手机号（5~15 位数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Schema(description = "国家/地区代码，默认 86")
    private String ctcode;
}

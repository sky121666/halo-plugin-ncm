package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 扫码登录用的二维码 */
@Data
@Schema(description = "扫码登录用的二维码")
public class NcmQrKey {

    @Schema(description = "二维码 key，轮询扫码状态时传回", requiredMode = Schema.RequiredMode.REQUIRED)
    private String key;

    @Schema(description = "二维码内容（网易云登录链接），由前端渲染成二维码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String qrurl;
}

package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NcmQrConfirmRequest(
    @Schema(description = "生成二维码时返回的会话标识", requiredMode = Schema.RequiredMode.REQUIRED)
    String key
) {
}

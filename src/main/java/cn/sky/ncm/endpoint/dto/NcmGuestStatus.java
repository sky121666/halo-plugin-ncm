package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "工具页的游客状态摘要", requiredProperties = {"state"})
public record NcmGuestStatus(
    @Schema(allowableValues = {"registered", "pending", "failed"}) String state,
    @Schema(description = "最近一次初始化尝试的时间") String lastAttempt) {
}

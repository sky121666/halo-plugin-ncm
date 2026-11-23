package cn.sky.ncm.setting;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"revision", "settings"})
public record NcmFrontstageConfig(Long revision, NcmFrontstageSettings settings) {
}

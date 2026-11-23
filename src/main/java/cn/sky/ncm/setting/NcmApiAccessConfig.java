package cn.sky.ncm.setting;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"revision", "settings"})
public record NcmApiAccessConfig(Long revision, NcmApiAccessSettings settings) {
}

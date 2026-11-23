package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 插件的设备身份与运行状态（不含任何密钥） */
@Data
@Schema(description = "插件的设备身份与运行状态（不含任何密钥）")
public class NcmDeviceStatus {

    @Schema(description = "设备 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String deviceId;

    @Schema(description = "xeapi 公钥是否就绪", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean xeapiReady;

    @Schema(description = "xeapi 公钥版本")
    private String xeapiKeyVersion;

    @Schema(description = "xeapi 公钥获取时间")
    private String xeapiKeyFetchedAt;

    @Schema(description = "xeapi 会话是否已建立", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean xeapiSession;

    @Schema(description = "游客身份是否已注册", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean anonymousReady;

    @Schema(description = "NMTID 是否已获取", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean nmtidReady;

    @Schema(description = "上次初始化时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastAttempt;

    @Schema(description = "上次初始化的错误，空串为无", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastError;

    @Schema(description = "上次保存时间")
    private String persistedAt;

    @Schema(description = "上次保存的错误，空串为无", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storeError;

    @Schema(description = "保存凭据的 Halo Secret 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String credentialSecrets;

    @Schema(description = "加密密钥来源", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"env", "file", "memory"})
    private String secretKeySource;

    @Schema(description = "加密密钥是否持久（重启后仍可解密）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean secretKeyPersistent;

    @Schema(description = "出站代理：空串为直连，否则为“已配置”", requiredMode = Schema.RequiredMode.REQUIRED)
    private String proxy;
}

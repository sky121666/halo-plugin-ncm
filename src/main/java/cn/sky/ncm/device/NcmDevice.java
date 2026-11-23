package cn.sky.ncm.device;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 插件的设备身份（name 固定为 "default"）。
 * 游客 token、xeapi 的 sk 和会话密钥存在 Halo Secret「ncm-device-default」里，这里只放非敏感字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "ncm.halo.run", version = "v1alpha1", kind = "NcmDevice",
    plural = "ncmdevices", singular = "ncmdevice")
public class NcmDevice extends AbstractExtension {

    private Spec spec;

    @Data
    public static class Spec {
        private String deviceId;
        private String wnmcid;
        private String nmtid;
        private String xeapiPublicKey;
        private String xeapiKeyVersion;
        private Long xeapiFetchedAt;
        private String xeapiSessionId;
        /** 保存敏感字段的 Halo Secret 名称。 */
        private String secretName;
        private String updatedAt;

        /** @deprecated 旧版本字段，只用于升级迁移，改存 Secret */
        @Deprecated
        private String anonymousToken;
        /** @deprecated 旧版本字段，只用于升级迁移，改存 Secret */
        @Deprecated
        private String xeapiSk;
        /** @deprecated 旧版本字段，只用于升级迁移，改存 Secret */
        @Deprecated
        private String xeapiSessionKey;
    }
}

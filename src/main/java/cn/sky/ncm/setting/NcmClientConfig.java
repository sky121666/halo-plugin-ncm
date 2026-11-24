package cn.sky.ncm.setting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "ncm.halo.run", version = "v1alpha1", kind = "NcmClientConfig",
    plural = "ncmclientconfigs", singular = "ncmclientconfig")
public class NcmClientConfig extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Schema(requiredProperties = {"clientId", "schemaVersion", "config"})
    public record Spec(String clientId, String ownerKey, int schemaVersion, Map<String, Object> config,
                       Map<String, Object> previousConfig) {
    }
}

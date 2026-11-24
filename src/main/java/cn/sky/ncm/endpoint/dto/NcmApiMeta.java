package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 一个网易云接口的等级和移植状态 */
@Data
@Schema(description = "一个网易云接口的等级和移植状态")
public class NcmApiMeta {

    @Schema(description = "接口名（上游 module 文件名）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "等级", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"public", "personal", "write", "auth", "admin"})
    private String tier;

    @Schema(description = "移植状态", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ready", "custom", "unsupported", "manual"})
    private String status;

    @Schema(description = "通用调用路径（相对 /apis/api.ncm.halo.run/v1alpha1），内部和未实现接口为空",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String path;

    @Schema(description = "接口已实现且具有通用调用入口，实际请求仍需满足权限与绑定要求",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean open;

    @Schema(description = "是否可使用游客身份执行后台只读调试", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean debuggable;
}

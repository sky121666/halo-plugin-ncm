package cn.sky.ncm.endpoint.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 接口清单 */
@Data
@Schema(description = "接口清单")
public class NcmApiMetaList {

    @Schema(description = "全部接口", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmApiMeta> items;

    @Schema(description = "接口总数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer total;
}

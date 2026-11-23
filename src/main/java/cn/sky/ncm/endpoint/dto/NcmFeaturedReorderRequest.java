package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/** 调整顺序 */
@Data
@Schema(description = "调整顺序")
public class NcmFeaturedReorderRequest {

    @Schema(description = "新顺序下的全部记录名", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> names;
}

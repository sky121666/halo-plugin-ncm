package cn.sky.ncm.endpoint.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 批量查询歌曲是否已红心 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量查询歌曲是否已红心")
public class NcmMeLikeCheck {

    @Schema(description = "键为歌曲 ID（字符串），值为是否已红心；请求里的每个 ID 都会出现", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, Boolean> liked;
}

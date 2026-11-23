package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 新建歌单 */
@Data
@Schema(description = "新建歌单")
public class NcmMePlaylistCreateRequest {

    @Schema(description = "歌单名，1~40 个字", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "0 公开（默认），10 隐私")
    private Integer privacy;
}

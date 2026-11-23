package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 加入歌单的歌曲 */
@Data
@Schema(description = "加入歌单的歌曲")
public class NcmMeTracksRequest {

    @Schema(description = "歌曲 ID，1~100 个", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> songIds;
}

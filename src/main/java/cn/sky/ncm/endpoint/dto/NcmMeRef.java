package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** ID 和名称（歌手、专辑等） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "ID 和名称（歌手、专辑等）")
public class NcmMeRef {

    @Schema(description = "ID")
    private String id;

    @Schema(description = "名称")
    private String name;
}

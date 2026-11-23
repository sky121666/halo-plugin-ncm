package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分页的歌曲列表 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页的歌曲列表")
public class NcmMeSongPage {

    @Schema(description = "本页从第几首开始（从 0 开始）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer offset;

    @Schema(description = "每页数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer limit;

    @Schema(description = "总数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer total;

    @Schema(description = "歌曲", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmMeSong> items;
}

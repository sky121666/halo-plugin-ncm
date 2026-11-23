package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 个人音乐空间里的一首歌 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "个人音乐空间里的一首歌")
public class NcmMeSong {

    @Schema(description = "歌曲 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "歌名")
    private String name;

    @Schema(description = "歌手")
    private List<NcmMeRef> artists;

    @Schema(description = "歌手名，用 / 分隔")
    private String artistNames;

    @Schema(description = "专辑")
    private NcmMeRef album;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "时长（毫秒）")
    private Long durationMs;

    @Schema(description = "时长，例如 3:45")
    private String durationText;

    @Schema(description = "收费类型：0/8 免费，1 会员，4 付费专辑")
    private Integer fee;

    @Schema(description = "是否会员歌曲")
    private Boolean vip;

    @Schema(description = "按版权信息估计能否完整播放；没有版权信息时不返回")
    private Boolean playable;

    @Schema(description = "在网站上打开的地址，例如 /music/song/123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sitePath;
}

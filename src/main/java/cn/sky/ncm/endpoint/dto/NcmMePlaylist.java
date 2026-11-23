package cn.sky.ncm.endpoint.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 个人音乐空间里的一个歌单 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "个人音乐空间里的一个歌单")
public class NcmMePlaylist {

    @Schema(description = "歌单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "歌单名")
    private String name;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "简介")
    private String description;

    @Schema(description = "歌曲数")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer trackCount;

    @Schema(description = "播放次数")
    private Long playCount;

    @Schema(description = "是否本人创建", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean own;

    @Schema(description = "是否“我喜欢的音乐”", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean likedSongs;

    @Schema(description = "是否隐私歌单")
    private Boolean privacy;

    @Schema(description = "创建者 UID")
    private String creatorId;

    @Schema(description = "创建者昵称")
    private String creatorName;

    @Schema(description = "本人是否已收藏（仅歌单详情里有）")
    private Boolean subscribed;

    @Schema(description = "在网站上打开的地址，例如 /music/playlist/123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sitePath;
}

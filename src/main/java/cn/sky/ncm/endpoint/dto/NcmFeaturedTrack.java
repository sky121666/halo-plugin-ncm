package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 预览里的一首歌 */
@Data
@Schema(description = "预览里的一首歌")
public class NcmFeaturedTrack {

    @Schema(description = "歌曲 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "歌名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "歌手")
    private String artists;

    @Schema(description = "专辑")
    private String album;

    @Schema(description = "时长，例如 4:29")
    private String duration;

    @Schema(description = "封面缩略图")
    private String coverUrl;

    @Schema(description = "游客视角的预估：full / trial / unavailable / pending", requiredMode = Schema.RequiredMode.REQUIRED)
    private String guest;

    @Schema(description = "站长账号视角的预估，没绑定时为空")
    private String site;

    @Schema(description = "游客身份抽样实测结果：full / trial / unavailable / pending，没实测时为空")
    private String measured;

    @Schema(description = "插件的播放地址（按当前登录账号取址）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String playUrl;
}

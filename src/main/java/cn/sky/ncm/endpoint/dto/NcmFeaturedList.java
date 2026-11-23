package cn.sky.ncm.endpoint.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/** 发布歌单列表（按 /music 上的顺序） */
@Data
@Schema(description = "发布歌单列表（按 /music 上的顺序）")
public class NcmFeaturedList {

    @Schema(description = "歌单", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmFeaturedItem> items;
}

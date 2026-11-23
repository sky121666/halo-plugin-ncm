package cn.sky.ncm.endpoint.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 本人创建和收藏的歌单 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "本人创建和收藏的歌单")
public class NcmMePlaylistList {

    @Schema(description = "网易云 UID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "歌单列表：“我喜欢的音乐”在最前，然后是自己创建的，最后是收藏的", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<NcmMePlaylist> items;

    @Schema(description = "歌单总数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer total;
}

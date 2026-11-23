package cn.sky.ncm.finder.vo;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** 搜索联想。groups 按网易云给出的顺序排列，空组已去掉。 */
@Value
@Builder
public class SuggestVo {
    String keyword;
    List<SuggestGroupVo> groups;

    public boolean isEmpty() {
        return groups == null || groups.isEmpty();
    }
}

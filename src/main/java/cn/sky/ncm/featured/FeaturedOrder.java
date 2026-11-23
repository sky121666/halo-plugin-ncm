package cn.sky.ncm.featured;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 歌单发布的排序规则：priority 越小越靠前（没填的排最后），相同时先添加的靠前，再按名称。 */
public final class FeaturedOrder {

    /** 排序间隔：重新排序后 priority 依次为 10、20、30…… */
    public static final int STEP = 10;

    public static final Comparator<NcmFeaturedPlaylist> COMPARATOR = Comparator
        .<NcmFeaturedPlaylist>comparingInt(FeaturedOrder::priorityOf)
        .thenComparing(FeaturedOrder::createdOf, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
        .thenComparing(FeaturedOrder::name, Comparator.nullsLast(Comparator.<String>naturalOrder()));

    private FeaturedOrder() {
    }

    public static List<NcmFeaturedPlaylist> sort(Collection<NcmFeaturedPlaylist> items) {
        return items.stream().sorted(COMPARATOR).toList();
    }

    /** 记录名（playlist-{歌单 ID}）。 */
    public static String name(NcmFeaturedPlaylist p) {
        return p.getMetadata() == null ? null : p.getMetadata().getName();
    }

    /** 现有最大的 priority（没有时为 0），新添加的排在它后面。 */
    public static int maxPriority(Collection<NcmFeaturedPlaylist> items) {
        int max = 0;
        for (NcmFeaturedPlaylist p : items) {
            if (p.getSpec() != null && p.getSpec().getPriority() != null) {
                max = Math.max(max, p.getSpec().getPriority());
            }
        }
        return max;
    }

    /**
     * 按站长给出的完整顺序重新编号（10、20、30……）。
     *
     * @param current   服务器上现有的全部记录名
     * @param requested 站长给出的新顺序，必须正好是 current 的一个排列
     * @return 记录名 → 新 priority（按新顺序）；和服务器上的列表不一致（缺、多、重复、为空）时返回 null
     */
    public static Map<String, Integer> renumber(List<String> current, List<String> requested) {
        if (current == null || requested == null || requested.size() != current.size()) {
            return null;
        }
        Set<String> known = new HashSet<>(current);
        Set<String> seen = new HashSet<>();
        Map<String, Integer> out = new LinkedHashMap<>();
        int i = 1;
        for (String n : requested) {
            if (n == null || !known.contains(n) || !seen.add(n)) {
                return null;
            }
            out.put(n, i++ * STEP);
        }
        return out;
    }

    static int priorityOf(NcmFeaturedPlaylist p) {
        Integer v = p.getSpec() == null ? null : p.getSpec().getPriority();
        return v == null ? Integer.MAX_VALUE : v;
    }

    static Instant createdOf(NcmFeaturedPlaylist p) {
        return p.getMetadata() == null ? null : p.getMetadata().getCreationTimestamp();
    }
}

package cn.sky.ncm.featured;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把旧设置「音乐页面 → 音乐页推荐歌单」（featuredPlaylistIds）归集为 {@link NcmFeaturedPlaylist}。
 * <p>
 * 规则（可以重复执行）：
 * <ul>
 *   <li>ID 先去掉前导零再判重（"001" 和 "1" 是同一个歌单），保留第一次出现的位置</li>
 *   <li>按旧设置里的顺序，追加在已有歌单后面，状态为“已发布”（和升级前一样显示在 /music）</li>
 *   <li>已经有同一个歌单的记录时跳过，不改它</li>
 *   <li>归集过的 ID 记在 ConfigMap 里：站长之后在后台删掉的歌单不会因为旧设置还在而再次出现；旧设置里新加的 ID 会继续归集</li>
 *   <li>不修改旧设置本身，也不碰账号和绑定</li>
 * </ul>
 */
public final class FeaturedMigration {

    public static final int STEP = 10;

    public record Item(String playlistId, int priority) {
    }

    /**
     * @param create  需要新建的歌单（按顺序）
     * @param marker  归集后应保存的“已归集 ID”
     * @param changed marker 是否有变化（没变化时不用写 ConfigMap）
     */
    public record Plan(List<Item> create, Set<String> marker, boolean changed) {
    }

    private FeaturedMigration() {
    }

    /**
     * @param legacyIds   旧设置里的歌单 ID（按顺序，可能带前导零或重复）
     * @param marker      已归集过的 ID
     * @param existingIds 已有记录的歌单 ID（包括草稿、已下架和正在删除的）
     * @param maxPriority 已有记录里最大的 priority；没有记录时传 0
     */
    public static Plan plan(List<String> legacyIds, Set<String> marker, Set<String> existingIds, int maxPriority) {
        Set<String> done = new LinkedHashSet<>();
        if (marker != null) {
            marker.stream().filter(FeaturedInput::validId).map(FeaturedInput::trimZeros).forEach(done::add);
        }
        Set<String> existing = new LinkedHashSet<>();
        if (existingIds != null) {
            existingIds.stream().filter(FeaturedInput::validId).map(FeaturedInput::trimZeros).forEach(existing::add);
        }
        List<Item> create = new ArrayList<>();
        int next = maxPriority;
        boolean changed = false;
        for (String raw : legacyIds == null ? List.<String>of() : legacyIds) {
            String s = raw == null ? null : raw.strip();
            if (!FeaturedInput.validId(s)) {
                continue;
            }
            String id = FeaturedInput.trimZeros(s);
            if (done.contains(id)) {
                continue;
            }
            if (!existing.contains(id)) {
                next += STEP;
                create.add(new Item(id, next));
            }
            done.add(id);
            changed = true;
        }
        return new Plan(List.copyOf(create), Set.copyOf(done), changed);
    }
}

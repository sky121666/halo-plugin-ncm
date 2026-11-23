package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

class FeaturedOrderTest {

    static NcmFeaturedPlaylist item(String id, Integer priority, Instant created) {
        var p = FeaturedService.newRecord(id, 0, false);
        p.getSpec().setPriority(priority);
        Metadata m = (Metadata) p.getMetadata();
        m.setCreationTimestamp(created);
        return p;
    }

    @Test
    void sortsByPriorityThenCreatedThenName() {
        Instant t = Instant.parse("2026-11-21T00:00:00Z");
        List<NcmFeaturedPlaylist> list = new ArrayList<>(List.of(
            item("3", 20, t), item("1", 10, t.plusSeconds(5)), item("2", 10, t), item("4", null, t), item("5", 20, t)));
        list.sort(FeaturedOrder.COMPARATOR);
        assertEquals(List.of("playlist-2", "playlist-1", "playlist-3", "playlist-5", "playlist-4"),
            list.stream().map(FeaturedOrder::name).toList());
    }

    @Test
    void renumber() {
        var current = List.of("playlist-1", "playlist-2", "playlist-3");
        assertEquals(Map.of("playlist-3", 10, "playlist-1", 20, "playlist-2", 30),
            FeaturedOrder.renumber(current, List.of("playlist-3", "playlist-1", "playlist-2")));
        // 和服务器上的列表不一致：缺一个、多一个、重复
        assertNull(FeaturedOrder.renumber(current, List.of("playlist-1", "playlist-2")));
        assertNull(FeaturedOrder.renumber(current, List.of("playlist-1", "playlist-2", "playlist-9")));
        assertNull(FeaturedOrder.renumber(current, List.of("playlist-1", "playlist-1", "playlist-2")));
        assertNull(FeaturedOrder.renumber(current, null));
    }
}

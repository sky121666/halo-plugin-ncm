package cn.sky.ncm.featured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.sky.ncm.featured.FeaturedMigration.Item;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeaturedMigrationTest {

    @Test
    void firstRunKeepsLegacyOrderAfterExisting() {
        var plan = FeaturedMigration.plan(List.of("30", "10", "20"), Set.of(), Set.of(), 0);
        assertEquals(List.of(new Item("30", 10), new Item("10", 20), new Item("20", 30)), plan.create());
        assertEquals(Set.of("10", "20", "30"), plan.marker());
        assertTrue(plan.changed());
        // 已经有歌单时追加在后面
        var after = FeaturedMigration.plan(List.of("1"), Set.of(), Set.of("9"), 40);
        assertEquals(List.of(new Item("1", 50)), after.create());
    }

    @Test
    void repeatedRunsCreateNothing() {
        var first = FeaturedMigration.plan(List.of("1", "2"), Set.of(), Set.of(), 0);
        var second = FeaturedMigration.plan(List.of("1", "2"), first.marker(), Set.of("1", "2"), 20);
        assertTrue(second.create().isEmpty());
        assertFalse(second.changed());
    }

    @Test
    void existingRecordsAreNotTouchedButMarked() {
        var plan = FeaturedMigration.plan(List.of("1", "2"), Set.of(), Set.of("1"), 10);
        assertEquals(List.of(new Item("2", 20)), plan.create());
        assertEquals(Set.of("1", "2"), plan.marker());
    }

    @Test
    void deletedByAdminDoesNotComeBack() {
        // 1 归集过、之后被站长删掉（不在 existing 里），2 是旧设置里新加的
        var plan = FeaturedMigration.plan(List.of("1", "2"), Set.of("1"), Set.of(), 0);
        assertEquals(List.of(new Item("2", 10)), plan.create());
    }

    @Test
    void invalidAndDuplicateIdsAreSkipped() {
        var plan = FeaturedMigration.plan(List.of("1", "abc", " 1 ", "0", "2"), Set.of(), Set.of(), 0);
        assertEquals(List.of(new Item("1", 10), new Item("2", 20)), plan.create());
        assertFalse(FeaturedMigration.plan(List.of(), Set.of(), Set.of(), 0).changed());
        assertFalse(FeaturedMigration.plan(null, null, null, 0).changed());
    }

    @Test
    void leadingZerosAreTrimmedBeforeDedupeKeepingFirstPosition() {
        var plan = FeaturedMigration.plan(List.of("0030", "10", "30", "010", "2"), Set.of(), Set.of(), 0);
        assertEquals(List.of(new Item("30", 10), new Item("10", 20), new Item("2", 30)), plan.create());
        assertEquals(Set.of("30", "10", "2"), plan.marker());
        // 已有记录和标记里的 ID 也按去零后比较
        var again = FeaturedMigration.plan(List.of("0030", "010"), Set.of("030"), Set.of("10"), 30);
        assertTrue(again.create().isEmpty());
        // 第二次执行不再新建
        var second = FeaturedMigration.plan(List.of("0030", "10", "2"), plan.marker(), Set.of("30", "10", "2"), 30);
        assertFalse(second.changed());
    }
}

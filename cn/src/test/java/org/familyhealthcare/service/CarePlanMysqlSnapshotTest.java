package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local comparator regressions only; never substitutes for native MySQL. */
class CarePlanMysqlSnapshotTest {
    private Map<String, List<String>> snapshot(String row, String allocator, String storage) {
        Map<String, List<String>> value = new TreeMap<>();
        value.put("rows:sys_role_menu", Collections.singletonList(row));
        value.put("table-storage", Collections.singletonList(storage));
        value.put("keys", Collections.singletonList("unique:role_id,menu_id"));
        value.put("allocator-sequences", Collections.singletonList(allocator));
        return value;
    }

    @Test void repeatMigrationIgnoresOnlyAllocatorAdvancement() {
        Map<String, List<String>> before = snapshot("id=5;role=2;menu=40", "sys_role_menu=6", "InnoDB:utf8mb4");
        Map<String, List<String>> after = snapshot("id=5;role=2;menu=40", "sys_role_menu=8", "InnoDB:utf8mb4");
        assertEquals(CarePlanMysqlIntegrationTest.comparisonSnapshot(before, false),
                CarePlanMysqlIntegrationTest.comparisonSnapshot(after, false));
        assertTrue(before.containsKey("allocator-sequences"), "Normalization must not mutate the complete original snapshot");
    }

    @Test void repeatMigrationStillDetectsChangedLogicalRowsAndStorage() {
        Map<String, List<String>> original = snapshot("id=5;role=2;menu=40", "sys_role_menu=6", "InnoDB:utf8mb4");
        assertNotEquals(CarePlanMysqlIntegrationTest.comparisonSnapshot(original, false),
                CarePlanMysqlIntegrationTest.comparisonSnapshot(snapshot("id=5;role=2;menu=41", "sys_role_menu=8", "InnoDB:utf8mb4"), false));
        assertNotEquals(CarePlanMysqlIntegrationTest.comparisonSnapshot(original, false),
                CarePlanMysqlIntegrationTest.comparisonSnapshot(snapshot("id=5;role=2;menu=40", "sys_role_menu=8", "MyISAM:utf8mb4"), false));
    }

    @Test void fullRestoreComparisonRetainsAllocatorState() {
        Map<String, List<String>> before = snapshot("id=5;role=2;menu=40", "sys_role_menu=6", "InnoDB:utf8mb4");
        Map<String, List<String>> after = snapshot("id=5;role=2;menu=40", "sys_role_menu=8", "InnoDB:utf8mb4");
        assertEquals(before, CarePlanMysqlIntegrationTest.comparisonSnapshot(before, true));
        assertNotEquals(CarePlanMysqlIntegrationTest.comparisonSnapshot(before, true),
                CarePlanMysqlIntegrationTest.comparisonSnapshot(after, true));
    }
}

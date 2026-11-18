package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.DimensionInfo;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDimensionRegistryTest {

    private static ConfigDimensionRegistry registry() {
        return new ConfigDimensionRegistry(new DimensionsSettings(
                new DimensionTarget("overworld", "minecraft:overworld"),
                new DimensionTarget("nether", "minecraft:the_nether"),
                new DimensionTarget("end", "minecraft:the_end")));
    }

    @Test
    void byServerResolvesExactMatches() {
        assertEquals(Optional.of(new DimensionInfo(DimensionId.OVERWORLD, "overworld")),
                registry().byServer("overworld"));
        assertEquals(Optional.of(new DimensionInfo(DimensionId.NETHER, "nether")),
                registry().byServer("nether"));
        assertEquals(Optional.of(new DimensionInfo(DimensionId.THE_END, "end")),
                registry().byServer("end"));
        assertTrue(registry().byServer("other").isEmpty());
        assertTrue(registry().byServer("OVERWORLD").isEmpty(), "matching is case-sensitive");
    }

    @Test
    void byDimensionResolvesExactMatches() {
        assertEquals(Optional.of(new DimensionInfo(DimensionId.OVERWORLD, "overworld")),
                registry().byDimension(DimensionId.OVERWORLD));
        assertEquals(Optional.of(new DimensionInfo(DimensionId.NETHER, "nether")),
                registry().byDimension(DimensionId.NETHER));
        assertEquals(Optional.of(new DimensionInfo(DimensionId.THE_END, "end")),
                registry().byDimension(DimensionId.THE_END));
        assertTrue(registry().byDimension(DimensionId.of("minecraft:custom_dim")).isEmpty());
    }

    @Test
    void allKeepsConfigurationOrderAndIsImmutableView() {
        List<DimensionInfo> all = registry().all();
        assertEquals(List.of(
                new DimensionInfo(DimensionId.OVERWORLD, "overworld"),
                new DimensionInfo(DimensionId.NETHER, "nether"),
                new DimensionInfo(DimensionId.THE_END, "end")), all);
    }
}

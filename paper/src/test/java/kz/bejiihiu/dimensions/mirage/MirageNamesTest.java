package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.config.MirageNamesSettings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirageNamesTest {

    private static MirageNamesSettings config(String overworld, String nether, String end) {
        return new MirageNamesSettings(overworld, nether, end);
    }

    @Test
    void defaultsWithDefaultLevelNameGiveBukkitConventionNames() {
        MirageNames names = MirageNames.resolve(config("world", "world_nether", "world_the_end"), "world");
        assertEquals("world", names.overworld());
        assertEquals("world_nether", names.nether());
        assertEquals("world_the_end", names.end());
    }

    @Test
    void defaultsWithCustomLevelNameDeriveFromLevelName() {
        MirageNames names = MirageNames.resolve(config("world", "world_nether", "world_the_end"), "survival");
        assertEquals("survival", names.overworld());
        assertEquals("survival_nether", names.nether());
        assertEquals("survival_the_end", names.end());
    }

    @Test
    void explicitOverrideWinsEvenWithCustomLevelName() {
        MirageNames names = MirageNames.resolve(config("over", "nether-stub", "end-stub"), "survival");
        assertEquals("over", names.overworld());
        assertEquals("nether-stub", names.nether());
        assertEquals("end-stub", names.end());
    }

    @Test
    void explicitPartialOverrideCountsAsOverrideAsLongAsAnyKeyDiffers() {
        // an operator who overrode only the end name gets exactly what they asked for,
        // not a level-name-derived mix
        MirageNames names = MirageNames.resolve(config("world", "world_nether", "custom_end"), "survival");
        assertEquals("world", names.overworld());
        assertEquals("world_nether", names.nether());
        assertEquals("custom_end", names.end());
    }

    @Test
    void blankLevelNameFallsBackToWorld() {
        MirageNames names = MirageNames.resolve(config("world", "world_nether", "world_the_end"), "  ");
        assertEquals("world", names.overworld());
        assertEquals("world_nether", names.nether());
    }

    @Test
    void nullLevelNameFallsBackToWorld() {
        MirageNames names = MirageNames.resolve(config("world", "world_nether", "world_the_end"), null);
        assertEquals("world", names.overworld());
    }

    @Test
    void fromLevelNameHandlesWhitespaceAndBlank() {
        assertEquals("terra", MirageNames.fromLevelName(" terra ").overworld());
        assertEquals("world", MirageNames.fromLevelName("  ").overworld());
        assertEquals("terra_nether", MirageNames.fromLevelName("terra").nether());
        assertEquals("terra_the_end", MirageNames.fromLevelName("terra").end());
    }
}

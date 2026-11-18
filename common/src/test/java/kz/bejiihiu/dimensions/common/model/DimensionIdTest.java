package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionIdTest {

    @Test
    void wellKnownIdsExist() {
        assertEquals("minecraft:overworld", DimensionId.OVERWORLD.key());
        assertEquals("minecraft:the_nether", DimensionId.NETHER.key());
        assertEquals("minecraft:the_end", DimensionId.THE_END.key());
        assertTrue(DimensionId.OVERWORLD.isOverworld());
        assertTrue(DimensionId.NETHER.isNether());
        assertTrue(DimensionId.THE_END.isTheEnd());
    }

    @Test
    void parsesAndFormatsCustomKeys() {
        DimensionId custom = DimensionId.of("myplugin:test_dimension");
        assertEquals("myplugin", custom.namespace());
        assertEquals("test_dimension", custom.path());
        assertEquals("myplugin:test_dimension", custom.toString());
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals(DimensionId.NETHER, DimensionId.of("  MINECRAFT:The_Nether "));
    }

    @Test
    void rejectsMalformedKeys() {
        for (String bad : new String[]{"", "no-colon", "a:b:c", "UPPER:OK ONLY UPPER", "with space: x",
                "minecraft:", ":path", "minecraft:ü"}) {
            assertThrows(IllegalArgumentException.class, () -> DimensionId.of(bad), "key: " + bad);
        }
    }

    @Test
    void mixedCaseIsNormalizedNotRejected() {
        // keys are canonicalized to lowercase rather than rejected outright
        assertEquals(DimensionId.of("minecraft:capital"), DimensionId.of("minecraft:Capital"));
        assertEquals(DimensionId.of("minecraft:the_nether"), DimensionId.of("MINECRAFT:THE_nether"));
    }

    @Test
    void equalityIsKeyBased() {
        assertEquals(DimensionId.of("minecraft:overworld"), DimensionId.OVERWORLD);
        assertNotEquals(DimensionId.OVERWORLD, DimensionId.NETHER);
    }
}

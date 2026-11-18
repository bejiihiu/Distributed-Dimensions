package kz.bejiihiu.dimensions.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionIdTest {

    @Test
    void wellKnownInstancesMatchVanillaKeys() {
        assertEquals("minecraft:overworld", DimensionId.OVERWORLD.key());
        assertEquals("minecraft:the_nether", DimensionId.NETHER.key());
        assertEquals("minecraft:the_end", DimensionId.THE_END.key());
        assertTrue(DimensionId.OVERWORLD.isOverworld());
        assertTrue(DimensionId.NETHER.isNether());
        assertTrue(DimensionId.THE_END.isTheEnd());
    }

    @Test
    void normalizesCaseAndWhitespace() {
        DimensionId id = DimensionId.of("  Minecraft:THE_Nether ");
        assertEquals("minecraft:the_nether", id.key());
    }

    @Test
    void splitsNamespaceAndPath() {
        DimensionId id = DimensionId.of("mymod:custom/dim");
        assertEquals("mymod", id.namespace());
        assertEquals("custom/dim", id.path());
        assertEquals("mymod:custom/dim", id.toString());
    }

    @Test
    void rejectsMalformedKeys() {
        assertThrows(IllegalArgumentException.class, () -> DimensionId.of("the_nether"));
        assertThrows(IllegalArgumentException.class, () -> DimensionId.of("minecraft:The-Nether!"));
        assertThrows(IllegalArgumentException.class, () -> DimensionId.of(""));
        assertThrows(IllegalArgumentException.class, () -> DimensionId.of("minecraft:white space"));
        assertThrows(NullPointerException.class, () -> DimensionId.of(null));
    }

    @Test
    void equalityIsValueBased() {
        assertEquals(DimensionId.of("minecraft:the_nether"), DimensionId.NETHER);
        assertNotEquals(DimensionId.NETHER, DimensionId.THE_END);
    }
}

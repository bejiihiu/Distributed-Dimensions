package kz.bejiihiu.dimensions.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimensionInfoTest {

    @Test
    void holdsDimensionAndServer() {
        DimensionInfo info = new DimensionInfo(DimensionId.NETHER, "nether");
        assertEquals(DimensionId.NETHER, info.dimension());
        assertEquals("nether", info.server());
    }

    @Test
    void trimsServerName() {
        assertEquals("nether", new DimensionInfo(DimensionId.NETHER, "  nether ").server());
    }

    @Test
    void rejectsBlankServer() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionInfo(DimensionId.NETHER, "   "));
    }

    @Test
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> new DimensionInfo(null, "nether"));
        assertThrows(NullPointerException.class, () -> new DimensionInfo(DimensionId.NETHER, null));
    }
}

package kz.bejiihiu.dimensions.common.nether;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBorderBoundsTest {

    @Test
    void containsIsLowInclusiveHighExclusive() {
        WorldBorderBounds border = new WorldBorderBounds(-10, -20, 30, 25);
        assertTrue(border.contains(-10, -20));
        assertTrue(border.contains(29.999, 24.999));
        assertFalse(border.contains(30, 0));
        assertFalse(border.contains(0, 25));
        assertFalse(border.contains(-10.001, 0));
        assertTrue(border.containsBlock(29, 24));
        assertFalse(border.containsBlock(30, 0));
    }

    @Test
    void clampStaysInsideAndUsesHighMinusOne() {
        WorldBorderBounds border = new WorldBorderBounds(-10, -20, 30, 25);
        assertEquals(-10, border.clampX(-100));
        assertEquals(29, border.clampX(100)); // east - 1.0
        assertEquals(-20, border.clampZ(-100));
        assertEquals(24, border.clampZ(100)); // south - 1.0
        assertEquals(5, border.clampX(5));
        assertEquals(5, border.clampZ(5));
    }

    @Test
    void scaledBoundsScaleFromOrigin() {
        WorldBorderBounds ow = WorldBorderBounds.DEFAULT_OVERWORLD;
        WorldBorderBounds nether = ow.scaled(1.0 / 8.0);
        assertEquals(-3_749_998.125, nether.west(), 1e-9);
        assertEquals(3_749_998.125, nether.east(), 1e-9);
        assertEquals(nether, WorldBorderBounds.DEFAULT_NETHER);
        assertEquals(3_749_998.125, WorldBorderBounds.DEFAULT_NETHER.east(), 1e-9);
    }

    @Test
    void unboundedContainsEverythingFinite() {
        assertTrue(WorldBorderBounds.UNBOUNDED.contains(1e12, -1e12));
        assertEquals(42.5, WorldBorderBounds.UNBOUNDED.clampX(42.5), 1e-9);
        assertEquals(-1234.25, WorldBorderBounds.UNBOUNDED.clampZ(-1234.25), 1e-9);
    }

    @Test
    void rejectsDegenerateBounds() {
        assertThrows(IllegalArgumentException.class, () -> new WorldBorderBounds(10, 0, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> new WorldBorderBounds(0, 10, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> new WorldBorderBounds(Double.NaN, 0, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> new WorldBorderBounds(0, 0, Double.POSITIVE_INFINITY, 5));
        assertThrows(IllegalArgumentException.class, () -> WorldBorderBounds.DEFAULT_OVERWORLD.scaled(0));
        assertThrows(IllegalArgumentException.class, () -> WorldBorderBounds.DEFAULT_OVERWORLD.scaled(-1));
    }

    @Test
    void yIsNotPartOfTheBorder() {
        WorldBorderBounds border = new WorldBorderBounds(-10, -10, 10, 10);
        long[] clamped = border.clampToBlock(100, 999, -100);
        assertEquals(9, clamped[0]);
        assertEquals(999, clamped[1]);
        assertEquals(-10, clamped[2]);
    }
}

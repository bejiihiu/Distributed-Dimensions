package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionTest {

    @Test
    void rejectsNonFiniteComponents() {
        assertThrows(IllegalArgumentException.class, () -> new Position(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, 0, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, 0, 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(0, 0, 0, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void clampsToSquareBorder() {
        Position p = new Position(100, 64, -100, 45, 10);
        Position clamped = p.clampToSquareBorder(0, 0, 50);
        assertEquals(50, clamped.x());
        assertEquals(-50, clamped.z());
        // Y and rotation untouched
        assertEquals(64, clamped.y());
        assertEquals(45, clamped.yaw());
    }

    @Test
    void borderClampHandlesNegativeOrigin() {
        Position p = new Position(-1000, 70, 1000, 0, 0);
        Position clamped = p.clampToSquareBorder(-500, 500, 100);
        assertEquals(-600, clamped.x());
        assertEquals(600, clamped.z());
    }

    @Test
    void rejectsBadClampArguments() {
        Position p = new Position(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> p.clampToSquareBorder(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> p.clampToSquareBorder(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> p.clampToHeight(10, 5));
    }

    @Test
    void heightClampClampsOnlyY() {
        Position p = new Position(10, -100, 20, 5, 5);
        assertEquals(-64, p.clampToHeight(-64, 320).y());
        Position high = new Position(10, 9999, 20);
        assertEquals(320, high.clampToHeight(-64, 320).y());
    }

    @Test
    void squaredDistancesAreSquared() {
        Position a = new Position(0, 0, 0);
        Position b = new Position(3, 4, 0);
        assertEquals(25.0, a.distanceSquaredTo(b));
        assertEquals(9.0, a.horizontalDistanceSquaredTo(b));
        assertTrue(a.horizontalDistanceSquaredTo(b) <= a.distanceSquaredTo(b));
    }
}

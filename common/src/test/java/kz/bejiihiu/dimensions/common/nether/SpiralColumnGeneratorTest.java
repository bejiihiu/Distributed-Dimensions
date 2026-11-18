package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiralColumnGeneratorTest {

    @Test
    void radiusZeroYieldsOnlyCenter() {
        List<BlockPos> columns = SpiralColumnGenerator.spiral(new BlockPos(5, 64, 5), 0);
        assertEquals(List.of(new BlockPos(5, 64, 5)), columns);
    }

    @Test
    void radiusOneYieldsNineColumnsInVanillaOrder() {
        List<BlockPos> columns = SpiralColumnGenerator.spiral(new BlockPos(0, 64, 0), 1);
        // the vanilla iterateInSquare(center, 1, EAST, SOUTH) order
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1),
                new BlockPos(0, 64, 1),
                new BlockPos(-1, 64, 1),
                new BlockPos(-1, 64, 0),
                new BlockPos(-1, 64, -1),
                new BlockPos(0, 64, -1),
                new BlockPos(1, 64, -1)
        ), columns);
    }

    @Test
    void radiusTwoCoversTheFiveByFiveSquare() {
        List<BlockPos> columns = SpiralColumnGenerator.spiral(new BlockPos(0, 64, 0), 2);
        assertEquals(25, columns.size());
        assertEquals(new BlockPos(0, 64, 0), columns.get(0));
        Set<BlockPos> all = new HashSet<>(columns);
        assertEquals(25, all.size()); // no duplicates
        for (BlockPos column : columns) {
            assertTrue(Math.abs(column.x()) <= 2 && Math.abs(column.z()) <= 2);
        }
    }

    @Test
    void radiusSixteenCoversThirtyThreeByThirtyThreeSquare() {
        List<BlockPos> columns = SpiralColumnGenerator.spiral(new BlockPos(7, 100, -3), 16);
        assertEquals(1089, columns.size()); // 33x33
        assertEquals(new BlockPos(7, 100, -3), columns.get(0));
        Set<BlockPos> all = new HashSet<>(columns);
        assertEquals(1089, all.size());
        for (BlockPos column : columns) {
            assertTrue(Math.abs(column.x() - 7) <= 16 && Math.abs(column.z() + 3) <= 16);
        }
        assertTrue(all.contains(new BlockPos(7 - 16, 100, -3 - 16)));
        assertTrue(all.contains(new BlockPos(7 + 16, 100, -3 + 16)));
        assertTrue(all.contains(new BlockPos(7 + 16, 100, -3 - 16)));
        assertTrue(all.contains(new BlockPos(7 - 16, 100, -3 + 16)));
        // and it does not step outside the square
        assertTrue(all.stream().noneMatch(b -> Math.abs(b.x() - 7) > 16 || Math.abs(b.z() + 3) > 16));
    }

    @Test
    void generatorIsDeterministic() {
        List<BlockPos> first = SpiralColumnGenerator.spiral(new BlockPos(3, 64, 9), 16);
        List<BlockPos> second = SpiralColumnGenerator.spiral(new BlockPos(3, 64, 9), 16);
        assertEquals(first, second);
    }

    @Test
    void negativeRadiusRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SpiralColumnGenerator.spiral(new BlockPos(0, 64, 0), -1));
    }
}

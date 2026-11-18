package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargestRectangleFinderTest {

    private static final PortalOrientation X = PortalOrientation.X_AXIS;
    private static final PortalOrientation Z = PortalOrientation.Z_AXIS;

    private static Predicate<BlockPos> of(Set<BlockPos> blocks) {
        return blocks::contains;
    }

    private static Set<BlockPos> planeX(int x, int yMin, int yMax, int zMin, int zMax) {
        Set<BlockPos> blocks = new HashSet<>();
        for (int y = yMin; y <= yMax; y++) {
            for (int z = zMin; z <= zMax; z++) {
                blocks.add(new BlockPos(x, y, z));
            }
        }
        return blocks;
    }

    @Test
    void rectangleCoversFullPlane() {
        Set<BlockPos> plane = planeX(0, 10, 13, 0, 3);
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(0, 10, 0), Z, of(plane));
        assertEquals(new PortalInnerRect(new BlockPos(0, 10, 0), Z, 4, 4), rect);
    }

    @Test
    void singleCenterBlockOnly() {
        Set<BlockPos> plane = new HashSet<>(Set.of(new BlockPos(0, 64, 0)));
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(0, 64, 0), Z, of(plane));
        assertEquals(new PortalInnerRect(new BlockPos(0, 64, 0), Z, 1, 1), rect);
    }

    @Test
    void holeSplitsRunAndLargestLobeWins() {
        // z=0..5, y=10..13, hole at (0, 12, 3..4)
        Set<BlockPos> plane = planeX(0, 10, 13, 0, 5);
        plane.remove(new BlockPos(0, 12, 3));
        plane.remove(new BlockPos(0, 12, 4));
        // anchor above the portal: nearest POI would be (0,13,0)
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(0, 13, 0), Z, of(plane));
        assertEquals(new PortalInnerRect(new BlockPos(0, 10, 0), Z, 3, 4), rect);
    }

    @Test
    void isolatedIslandRectangleIsFound() {
        // two separate 2x2 lobes far apart; the largest single rectangle around the center is the near one
        Set<BlockPos> plane = new HashSet<>();
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                plane.add(new BlockPos(0, 10 + dy, dz));
            }
        }
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(0, 10, 0), Z, of(plane));
        assertEquals(new PortalInnerRect(new BlockPos(0, 10, 0), Z, 2, 2), rect);
    }

    @Test
    void limitsAreRespected() {
        // a 30-wide plane; primary limit 10 each way => rectangle spans at most 21 columns
        Set<BlockPos> plane = planeX(0, 10, 12, 0, 29);
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(0, 11, 15), Z, 10, 21, of(plane));
        assertTrue(rect.width() <= 21, "rectangle must not exceed the width limit: " + rect.width());
        assertTrue(rect.height() <= 21);
    }

    @Test
    void worksForXAxisOrientation() {
        Set<BlockPos> plane = new HashSet<>();
        for (int x = 100; x <= 103; x++) {
            for (int y = 64; y <= 66; y++) {
                plane.add(new BlockPos(x, y, 7));
            }
        }
        PortalInnerRect rect = LargestRectangleFinder.largestRectangle(
                new BlockPos(100, 64, 7), X, of(plane));
        assertEquals(new PortalInnerRect(new BlockPos(100, 64, 7), X, 4, 3), rect);
    }

    @Test
    void histogramFallbackProducesLargestBar() {
        assertEquals(4, LargestRectangleFinder.findLargestHistogramRectangle(new int[]{4, 1, 1, 1})[2]);
        // exact indices for a tie-prone case: first-found wins on equal area (strict >)
        int[] result = LargestRectangleFinder.findLargestHistogramRectangle(new int[]{2, 2});
        assertEquals(0, result[0]);
        assertEquals(1, result[1]);
        assertEquals(2, result[2]);
    }
}

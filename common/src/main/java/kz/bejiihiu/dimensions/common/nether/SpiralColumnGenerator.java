package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the vanilla spiral-column iteration used by portal creation siting
 * (BlockPos.iterateInSquare(center, radius, EAST, SOUTH) in the decompiled sources): the square
 * ring expands by one block per leg, turning EAST → SOUTH → WEST → NORTH, and the center itself
 * is the first column visited.
 *
 * <p>For radius 16 this yields 1089 columns — the full 33x33 square around the target including
 * the center, ring by ring. The order is part of the vanilla algorithm (the first equal-quality
 * site found in spiral order wins on ties), so it is not sorted afterwards.
 */
final class SpiralColumnGenerator {

    // EAST, SOUTH, WEST, NORTH — the vanilla direction order for this iteration
    private static final int[][] DIRECTIONS = {{1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}};

    private SpiralColumnGenerator() {
    }

    static List<BlockPos> spiral(BlockPos center, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0, got " + radius);
        }
        int totalCapacity = (2 * radius + 1) * (2 * radius + 1);
        List<BlockPos> out = new ArrayList<>(totalCapacity + 1);
        // vanilla starts one step before the center along the second direction, so the
        // first emitted column is the center itself
        BlockPos last = new BlockPos(center.x(), center.y(), center.z() + 1);
        int legs = 4 * radius;
        int leg = -1;
        int legSize = 0;
        int legIndex = 0;
        while (true) {
            int[] direction = DIRECTIONS[(leg + 4) % 4];
            BlockPos cursor = new BlockPos(last.x() + direction[0], last.y(), last.z() + direction[2]);
            last = cursor;
            if (legIndex >= legSize) {
                if (leg >= legs) {
                    return out;
                }
                leg++;
                legIndex = 0;
                legSize = leg / 2 + 1;
            }
            legIndex++;
            out.add(cursor);
        }
    }
}

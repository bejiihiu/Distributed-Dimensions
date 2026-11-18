package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.function.Predicate;

/**
 * Port of the vanilla "largest rectangle" routine (BlockLocating.getLargestRectangle in the
 * decompiled 1.21.x sources): given a predicate and a known-valid center block, find the largest
 * axis-aligned rectangle containing it whose blocks all pass the predicate.
 *
 * <p>This is how vanilla determines the exact portal rectangle once the nearest portal block has
 * been chosen — not by flood fill, but by this histogram search. It finds a rectangle inscribed in
 * the same plane, which for a clean portal equals the flood-fill bounds, and for a damaged portal
 * (a crater in the middle of the frame) returns the largest intact inscribed rectangle, exactly
 * like vanilla.
 *
 * <p>The secondary axis is always Y; the primary axis is the portal's width axis (X for
 * {@link PortalOrientation#X_AXIS}, Z for {@link PortalOrientation#Z_AXIS}). Limits mirror the
 * vanilla 21x21 portal maximum.
 */
public final class LargestRectangleFinder {

    /** Vanilla maximum extent along either axis, in blocks (portal max width/height is 21). */
    public static final int MAX_EXTENT = 21;

    private LargestRectangleFinder() {
    }

    private record IntBounds(int min, int max) {
    }

    /**
     * Finds the largest predicate-passing rectangle containing {@code center}, in the plane of
     * {@code orientation} (the width axis) and Y.
     *
     * @param center      a block position that passes {@code predicate} (not verified — a center
     *                    that fails the predicate yields a degenerate rectangle)
     * @param orientation which horizontal axis the rectangle spans
     * @param widthLimit  max blocks to walk along the width axis in either direction (21 in vanilla)
     * @param heightLimit max blocks to walk along Y in either direction (21 in vanilla)
     * @param predicate   block test; called with positions offset from the center
     * @return the largest rectangle found, anchored at its lower-left corner, never null
     */
    public static PortalInnerRect largestRectangle(
            BlockPos center, PortalOrientation orientation, int widthLimit, int heightLimit, Predicate<BlockPos> predicate) {
        int[] negWidth = orientation == PortalOrientation.X_AXIS ? new int[]{-1, 0, 0} : new int[]{0, 0, -1};
        int[] posWidth = negate(negWidth);
        int[] negHeight = {0, -1, 0};
        int[] posHeight = {0, 1, 0};

        int negScan = moveWhile(predicate, center, negWidth, widthLimit);
        int posScan = moveWhile(predicate, center, posWidth, widthLimit);
        int centerIndex = negScan;
        IntBounds[] columnBounds = new IntBounds[negScan + 1 + posScan];
        columnBounds[centerIndex] = new IntBounds(
                moveWhile(predicate, center, negHeight, heightLimit),
                moveWhile(predicate, center, posHeight, heightLimit));
        int heightCenter = columnBounds[centerIndex].min;

        for (int m = 1; m <= negScan; m++) {
            IntBounds previous = columnBounds[centerIndex - (m - 1)];
            BlockPos column = offset(center, negWidth, m);
            columnBounds[centerIndex - m] = new IntBounds(
                    moveWhile(predicate, column, negHeight, previous.min),
                    moveWhile(predicate, column, posHeight, previous.max));
        }
        for (int m = 1; m <= posScan; m++) {
            IntBounds previous = columnBounds[centerIndex + m - 1];
            BlockPos column = offset(center, posWidth, m);
            columnBounds[centerIndex + m] = new IntBounds(
                    moveWhile(predicate, column, negHeight, previous.min),
                    moveWhile(predicate, column, posHeight, previous.max));
        }

        int bestStart = 0;
        int bestBottom = 0;
        int bestWidth = 0;
        int bestHeight = 0;
        int[] heights = new int[columnBounds.length];
        for (int q = heightCenter; q >= 0; q--) {
            for (int r = 0; r < columnBounds.length; r++) {
                IntBounds bounds = columnBounds[r];
                int from = heightCenter - bounds.min;
                int to = heightCenter + bounds.max;
                heights[r] = q >= from && q <= to ? to + 1 - q : 0;
            }
            int[] biggest = findLargestHistogramRectangle(heights);
            int width = biggest[1] - biggest[0] + 1;
            int height = biggest[2];
            if (width * height > bestWidth * bestHeight) {
                bestStart = biggest[0];
                bestBottom = q;
                bestWidth = width;
                bestHeight = height;
            }
        }

        BlockPos lowerLeft = orientation == PortalOrientation.X_AXIS
                ? new BlockPos(center.x() + (bestStart - centerIndex), center.y() + (bestBottom - heightCenter), center.z())
                : new BlockPos(center.x(), center.y() + (bestBottom - heightCenter), center.z() + (bestStart - centerIndex));
        return new PortalInnerRect(lowerLeft, orientation, bestWidth, bestHeight);
    }

    /** Convenience overload using the vanilla 21x21 limits. */
    public static PortalInnerRect largestRectangle(BlockPos center, PortalOrientation orientation, Predicate<BlockPos> predicate) {
        return largestRectangle(center, orientation, MAX_EXTENT, MAX_EXTENT, predicate);
    }

    private static int moveWhile(Predicate<BlockPos> predicate, BlockPos start, int[] direction, int limit) {
        int count = 0;
        BlockPos current = start;
        while (count < limit) {
            current = new BlockPos(current.x() + direction[0], current.y() + direction[1], current.z() + direction[2]);
            if (!predicate.test(current)) {
                break;
            }
            count++;
        }
        return count;
    }

    private static BlockPos offset(BlockPos pos, int[] direction, int steps) {
        return new BlockPos(pos.x() + direction[0] * steps, pos.y() + direction[1] * steps, pos.z() + direction[2] * steps);
    }

    private static int[] negate(int[] direction) {
        return new int[]{-direction[0], -direction[1], -direction[2]};
    }

    /**
     * Largest rectangle inscribed in a histogram. Returns {@code {startIndex, endIndexInclusive,
     * height}}. Port of the vanilla stack-based algorithm.
     */
    static int[] findLargestHistogramRectangle(int[] heights) {
        int[] stack = new int[heights.length + 1];
        int stackTop = 0;
        stack[stackTop] = 0;
        int i = 0;
        int j = 0;
        int k = 0;
        for (int l = 1; l <= heights.length; l++) {
            int m = l == heights.length ? 0 : heights[l];
            while (stackTop >= 0) {
                int n = heights[stack[stackTop]];
                if (m >= n) {
                    stackTop++;
                    stack[stackTop] = l;
                    break;
                }
                stackTop--;
                int o = stackTop < 0 ? 0 : stack[stackTop] + 1;
                if (n * (l - o) > k * (j - i)) {
                    j = l;
                    i = o;
                    k = n;
                }
            }
            if (stackTop < 0) {
                stackTop = 0;
                stack[stackTop] = l;
            }
        }
        return new int[]{i, j - 1, k};
    }
}

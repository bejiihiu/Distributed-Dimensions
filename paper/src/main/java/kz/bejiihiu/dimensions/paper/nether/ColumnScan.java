package kz.bejiihiu.dimensions.paper.nether;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * The creation-site scan reads whole columns across many chunks — 33x33 columns over the
 * full height. Chunk snapshots make those reads safe anywhere; the problem is the size of
 * the data if we ship raw blocks to the planner, so the gathering phase compresses each
 * column into two bitsets (one bit per Y in the dimension). The planner then runs against
 * this offline view with no world access at all.
 */
final class ColumnScan {

    private final int bottomY;
    private final int topY;
    private final int logicalHeight;

    private final Map<Long, Column> columns = new HashMap<>();

    ColumnScan(int bottomY, int topY, int logicalHeight) {
        this.bottomY = bottomY;
        this.topY = topY;
        this.logicalHeight = logicalHeight;
    }

    int bottomY() {
        return bottomY;
    }

    int topY() {
        return topY;
    }

    int logicalHeight() {
        return logicalHeight;
    }

    void put(int x, int z, int motionBlockingTopY, BitSet replaceable, BitSet solid) {
        columns.put(key(x, z), new Column(motionBlockingTopY, (BitSet) replaceable.clone(), (BitSet) solid.clone()));
    }

    int motionBlockingTopY(int x, int z) {
        Column column = columns.get(key(x, z));
        return column != null ? column.motionBlockingTopY : bottomY;
    }

    boolean isReplaceable(int x, int y, int z) {
        Column column = columns.get(key(x, z));
        if (column == null) {
            return false;
        }
        int index = y - bottomY;
        return index >= 0 && index < column.replaceable.length() && column.replaceable.get(index);
    }

    boolean isSolid(int x, int y, int z) {
        Column column = columns.get(key(x, z));
        if (column == null) {
            return false;
        }
        int index = y - bottomY;
        return index >= 0 && index < column.solid.length() && column.solid.get(index);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private record Column(int motionBlockingTopY, BitSet replaceable, BitSet solid) {
    }
}

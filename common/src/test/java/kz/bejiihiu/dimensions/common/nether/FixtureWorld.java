package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Synthetic world for planner tests. Everything is AIR by default; columns can be filled with
 * solid ground, fluids, or fragile grass-like plants. Out-of-dimension queries behave like
 * vanilla void air: not solid, but "replaceable" — which makes the foundation row impossible
 * there, forcing a forced placement when nowhere else is viable.
 */
final class FixtureWorld implements WorldAccess {

    enum Kind {
        SOLID,   // blocks motion and light (not replaceable)
        AIR,     // replaceable, no fluid
        PLANT,   // grass-like: replaceable, does not block motion
        WATER,   // fluid: not replaceable, not solid, counts for motion-blocking heightmap
        LAVA     // fluid: same as WATER for our purposes
    }

    private final int bottomY;
    private final int topY;
    private final int logicalHeight;
    private final Map<Long, Kind> blocks = new HashMap<>();
    private final Map<Long, Integer> motionTopCache = new HashMap<>();

    FixtureWorld(int bottomY, int topY) {
        this(bottomY, topY, topY - bottomY);
    }

    FixtureWorld(int bottomY, int topY, int logicalHeight) {
        this.bottomY = bottomY;
        this.topY = topY;
        this.logicalHeight = logicalHeight;
    }

    static FixtureWorld overworld() {
        return new FixtureWorld(-64, 320, 384);
    }

    static FixtureWorld nether() {
        return new FixtureWorld(0, 128, 128);
    }

    FixtureWorld set(int x, int y, int z, Kind kind) {
        blocks.put(key(x, y, z), kind);
        motionTopCache.clear();
        return this;
    }

    FixtureWorld fillBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Kind kind) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocks.put(key(x, y, z), kind);
                }
            }
        }
        motionTopCache.clear();
        return this;
    }

    /** Solid floor filling the XZ plane [-64..64] up to {@code groundTop}. */
    FixtureWorld flatGround(int groundTop) {
        return flatGround(-64, 64, -64, 64, bottomY, groundTop);
    }

    FixtureWorld flatGround(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        return fillBox(minX, maxX, minY, maxY, minZ, maxZ, Kind.SOLID);
    }

    private Kind kindAt(BlockPos pos) {
        return blocks.getOrDefault(key(pos.x(), pos.y(), pos.z()), Kind.AIR);
    }

    @Override
    public int bottomY() {
        return bottomY;
    }

    @Override
    public int topY() {
        return topY;
    }

    @Override
    public int logicalHeight() {
        return logicalHeight;
    }

    @Override
    public int motionBlockingTopY(int x, int z) {
        long column = (((long) x) << 32) | (z & 0xffffffffL);
        Integer cached = motionTopCache.get(column);
        if (cached != null) {
            return cached;
        }
        int top = bottomY;
        for (Map.Entry<Long, Kind> entry : blocks.entrySet()) {
            long k = entry.getKey();
            int bx = (int) (k >> 32);
            int by = (int) ((k >> 16) & 0xffff);
            int bz = (int) (k & 0xffff);
            if (bx != x || bz != z) {
                continue;
            }
            Kind kind = entry.getValue();
            if (kind == Kind.SOLID || kind == Kind.WATER || kind == Kind.LAVA) {
                top = Math.max(top, by + 1);
            }
        }
        motionTopCache.put(column, top);
        return top;
    }

    @Override
    public boolean isReplaceable(BlockPos pos) {
        if (pos.y() < bottomY || pos.y() >= topY) {
            // void air: replaceable but never solid
            return true;
        }
        Kind kind = kindAt(pos);
        return kind == Kind.AIR || kind == Kind.PLANT;
    }

    @Override
    public boolean isSolid(BlockPos pos) {
        if (pos.y() < bottomY || pos.y() >= topY) {
            return false;
        }
        return kindAt(pos) == Kind.SOLID;
    }

    private static long key(int x, int y, int z) {
        // y and z both fit in 16 bits for world height ranges (|y| < 2^15, |z| < 2^15 here)
        return (((long) x) << 32) | (((long) y) & 0xffffL) << 16 | (z & 0xffffL);
    }
}

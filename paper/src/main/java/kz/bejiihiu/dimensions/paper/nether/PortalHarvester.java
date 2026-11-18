package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Region-thread-side chunk/column reads for the destination-side portal work. Snapshots make
 * the reads allocation-light and consistent; the motion-blocking heightmap read needs the
 * live world and therefore stays on the region thread for the chunk.
 */
final class PortalHarvester {

    private PortalHarvester() {
    }

    /**
     * Every NETHER_PORTAL block with a horizontal {@code Orientable} axis inside the chunk.
     * The snapshot is the authority — stale index entries are corrected by reconciling against
     * this set, never trusted on their own.
     */
    static List<BlockPos> portalBlocksInChunk(ChunkSnapshot snapshot, int chunkX, int chunkZ, int minY, int maxY) {
        List<BlockPos> out = new ArrayList<>();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (snapshot.getBlockType(x, y, z) != Material.NETHER_PORTAL) {
                        continue;
                    }
                    // only blocks carrying a horizontal axis are portal surfaces vanilla counts
                    if (snapshot.getBlockData(x, y, z) instanceof Orientable orientable
                            && (orientable.getAxis() == org.bukkit.Axis.X || orientable.getAxis() == org.bukkit.Axis.Z)) {
                        out.add(new BlockPos(baseX + x, y, baseZ + z));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Compresses every column of the chunk into the bitsets the creation planner needs.
     * {@code motionBlockingTopY} follows vanilla's "first free position above the highest
     * motion-blocking/fluid block" semantic; an empty column reports {@code bottomY} (the
     * planner then finds no site and falls back to forced placement, as vanilla does).
     */
    static void gatherColumnBits(World world, ChunkSnapshot snapshot, int chunkX, int chunkZ,
                                 int bottomY, int topY, ColumnScan scan) {
        int height = topY - bottomY;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                BitSet replaceable = new BitSet(height);
                BitSet solid = new BitSet(height);
                for (int y = bottomY; y < topY; y++) {
                    Material type = snapshot.getBlockType(lx, y, lz);
                    int index = y - bottomY;
                    if (isReplaceableMaterialFastPath(type) || isReplaceable(snapshot, lx, y, lz, type)) {
                        replaceable.set(index);
                    }
                    if (NetherMaterials.isSolid(type)) {
                        solid.set(index);
                    }
                }
                int highest;
                try {
                    highest = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                } catch (Throwable t) {
                    // treat an unreadable column as void; the foundation check decides
                    highest = bottomY - 1;
                }
                int motionTop = highest < bottomY ? bottomY : Math.min(topY, highest + 1);
                scan.put(x, z, motionTop, replaceable, solid);
            }
        }
    }

    /** Air family — the overwhelmingly common replaceable blocks; never needs a BlockData read. */
    private static boolean isReplaceableMaterialFastPath(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static boolean isReplaceable(org.bukkit.ChunkSnapshot snapshot, int localX, int y, int localZ, Material type) {
        return NetherMaterials.isReplaceableNoFluid(snapshot.getBlockData(localX, y, localZ), type);
    }

    /** Captures a snapshot of the given chunk (must be loaded — typically right after the async load). */
    static ChunkSnapshot snapshotOf(Chunk chunk) {
        return chunk.getChunkSnapshot(false, false, false);
    }
}

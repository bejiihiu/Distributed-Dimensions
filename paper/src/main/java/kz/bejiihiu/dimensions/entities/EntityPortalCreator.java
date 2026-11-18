package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.nether.PlannedPortalBlock;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlan;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.nether.WorldAccess;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.Material;

import java.util.List;
import java.util.function.Consumer;

/**
 * Executes a {@link PortalCreationPlan} against a Bukkit world. Planning happens on the
 * caller's region thread (the read view is the hint region — neighbour-chunk reads are
 * best-effort, the same trade the player-side nether wave will make). Placement of the
 * plan blocks is grouped per chunk and fanned out to the owning regions; the completion
 * callback fires when every block is placed, from whichever region finished last.
 *
 * <p>The portal blocks get their {@code HORIZONTAL_AXIS} from the plan's orientation;
 * frame blocks are obsidian, forced-placement platform is obsidian with air pockets.
 */
public final class EntityPortalCreator {

    private EntityPortalCreator() {
    }

    /** Read-only world view over the Bukkit world for the planner. */
    public static WorldAccess worldAccess(World world) {
        return new WorldAccess() {
            @Override
            public int bottomY() {
                return world.getMinHeight();
            }

            @Override
            public int topY() {
                return world.getMaxHeight();
            }

            @Override
            public int logicalHeight() {
                return world.getLogicalHeight();
            }

            @Override
            public int motionBlockingTopY(int x, int z) {
                return world.getHighestBlockAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING).getY() + 1;
            }

            @Override
            public boolean isReplaceable(BlockPos pos) {
                org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                return block.isReplaceable() && !block.isLiquid();
            }

            @Override
            public boolean isSolid(BlockPos pos) {
                return world.getBlockAt(pos.x(), pos.y(), pos.z()).getType().isSolid();
            }
        };
    }

    /** The destination world border as planner bounds (2D clamp, vanilla semantics). */
    public static WorldBorderBounds border(World world) {
        org.bukkit.WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0;
        double cx = border.getCenter().getX();
        double cz = border.getCenter().getZ();
        return new WorldBorderBounds(cx - half, cz - half, cx + half, cz + half);
    }

    /**
     * Place the plan, block by block, fanned out to the owning regions. Calls
     * {@code onComplete} on the region that owns the portal origin chunk once every block
     * is written. Runs all placement work from the calling thread's perspective without
     * blocking it.
     */
    public static void place(Schedulers schedulers, World world, PortalCreationPlan plan,
            Consumer<Boolean> onComplete) {
        List<PlannedPortalBlock> blocks = plan.blocks();
        java.util.Map<Long, List<PlannedPortalBlock>> byChunk = new java.util.HashMap<>();
        for (PlannedPortalBlock block : blocks) {
            int cx = Math.floorDiv(block.at().x(), 16);
            int cz = Math.floorDiv(block.at().z(), 16);
            byChunk.computeIfAbsent(((long) cx << 32) | (cz & 0xffffffffL), k -> new java.util.ArrayList<>()).add(block);
        }
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(byChunk.size());
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean(false);
        for (java.util.Map.Entry<Long, List<PlannedPortalBlock>> entry : byChunk.entrySet()) {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            List<PlannedPortalBlock> chunkBlocks = entry.getValue();
            schedulers.runOnRegion(world, cx, cz, () -> {
                try {
                    for (PlannedPortalBlock planned : chunkBlocks) {
                        placeBlock(world, planned, plan.orientation());
                    }
                } catch (RuntimeException e) {
                    failed.set(true);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        int originCx = Math.floorDiv(plan.origin().x(), 16);
                        int originCz = Math.floorDiv(plan.origin().z(), 16);
                        schedulers.runOnRegion(world, originCx, originCz,
                                () -> onComplete.accept(!failed.get()));
                    }
                }
            });
        }
    }

    private static void placeBlock(World world, PlannedPortalBlock planned, PortalOrientation orientation) {
        org.bukkit.block.Block block = world.getBlockAt(planned.at().x(), planned.at().y(), planned.at().z());
        switch (planned.type()) {
            case AIR -> block.setType(Material.AIR, false);
            case OBSIDIAN -> block.setType(Material.OBSIDIAN, false);
            case NETHER_PORTAL -> {
                block.setBlockData(portalBlockData(orientation), false);
            }
        }
    }

    private static BlockData portalBlockData(PortalOrientation orientation) {
        BlockData data = org.bukkit.Bukkit.createBlockData(Material.NETHER_PORTAL);
        if (data instanceof Orientable orientable) {
            // NETHER_PORTAL carries HORIZONTAL_AXIS (X or Z)
            orientable.setAxis(orientation == PortalOrientation.X_AXIS ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
        }
        return data;
    }
}

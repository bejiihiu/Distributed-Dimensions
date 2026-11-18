package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.nether.NetherPortalConstants;
import kz.bejiihiu.dimensions.common.nether.PlannedPortalBlock;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlan;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlan;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlanner;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;

import org.bukkit.Axis;
import org.bukkit.Material;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan → placement table: the only block that carries an axis is the portal block; the plan's
 * orientation decides X vs Z; every other block is placed without orientation. Also asserts the
 * plan keeps the placement order (platform → frame → portal) that the executor relies on.
 */
class NetherPortalBuildTest {

    @Test
    void portalBlocksCarryThePlanAxis() {
        PlannedPortalBlock portal = new PlannedPortalBlock(new BlockPos(0, 64, 0), PlannedPortalBlock.Type.NETHER_PORTAL);
        assertEquals(Axis.X, NetherPortalBuild.placementOf(portal, PortalOrientation.X_AXIS).axis());
        assertEquals(Axis.Z, NetherPortalBuild.placementOf(portal, PortalOrientation.Z_AXIS).axis());
        assertEquals(Material.NETHER_PORTAL, NetherPortalBuild.placementOf(portal, PortalOrientation.X_AXIS).material());
    }

    @Test
    void nonPortalBlocksNeverCarryAnAxis() {
        assertNull(NetherPortalBuild.placementOf(
                new PlannedPortalBlock(new BlockPos(0, 63, 0), PlannedPortalBlock.Type.OBSIDIAN),
                PortalOrientation.X_AXIS).axis());
        assertNull(NetherPortalBuild.placementOf(
                new PlannedPortalBlock(new BlockPos(0, 64, 0), PlannedPortalBlock.Type.AIR),
                PortalOrientation.Z_AXIS).axis());
        assertEquals(Material.OBSIDIAN, NetherPortalBuild.placementOf(
                new PlannedPortalBlock(new BlockPos(0, 63, 0), PlannedPortalBlock.Type.OBSIDIAN),
                PortalOrientation.X_AXIS).material());
        assertEquals(Material.AIR, NetherPortalBuild.placementOf(
                new PlannedPortalBlock(new BlockPos(0, 64, 0), PlannedPortalBlock.Type.AIR),
                PortalOrientation.Z_AXIS).material());
    }

    @Test
    void createdPlanOrdersPlatformThenFrameThenPortal() {
        // an all-air world with solid ground at y=0 forces a normal (non-forced) build at the surface
        ColumnScan scan = flatWorldColumnScan(-64, 320, 384);
        var plan = PortalCreationPlanner.plan(
                new BlockPos(0, 64, 0), PortalOrientation.Z_AXIS, new OfflineWorldAccess(scan),
                WorldBorderBounds.UNBOUNDED);

        assertTrue(plan.isPresent());
        List<PlannedPortalBlock> blocks = plan.get().blocks();
        int lastObsidian = lastIndexOfType(blocks, PlannedPortalBlock.Type.OBSIDIAN);
        int firstPortalBlock = firstIndexOfType(blocks, PlannedPortalBlock.Type.NETHER_PORTAL);
        // no portal block may appear before the frame; non-forced plans have no AIR at all
        assertTrue(firstPortalBlock > lastObsidian, "portal blocks come after the frame");
        assertTrue(blocks.stream().noneMatch(block -> block.type() == PlannedPortalBlock.Type.AIR),
                "a surface build on replaceable ground never clears cells");
        // the axis on portal blocks follows the plan orientation
        for (PlannedPortalBlock block : blocks) {
            NetherPortalBuild.Placement placement = NetherPortalBuild.placementOf(block, plan.get().orientation());
            if (placement.isPortal()) {
                assertEquals(Axis.Z, placement.axis());
            }
        }
        PortalInnerRect inner = plan.get().innerRect();
        assertEquals(NetherPortalConstants.PORTAL_INNER_WIDTH, inner.width());
        assertEquals(NetherPortalConstants.PORTAL_INNER_HEIGHT, inner.height());
    }

    @Test
    void forcedPlacementProducesPlatformAndAirPocketBeforeAnythingElse() {
        // a world of nothing but solid stone: no replaceable run anywhere → forced placement
        ColumnScan scan = allSolidColumnScan(-64, 320, 384);
        var plan = PortalCreationPlanner.plan(
                new BlockPos(0, 100, 0), PortalOrientation.X_AXIS, new OfflineWorldAccess(scan),
                WorldBorderBounds.UNBOUNDED);

        assertTrue(plan.isPresent());
        PortalCreationPlan chosen = plan.get();
        assertTrue(chosen.forcedPlacement(), "no site exists → forced placement, like vanilla");
        assertTrue(chosen.obsidianPlatform());
        // forced placement anchors y inside [max(bottomY + 1, 70), maxPlaceable - 9]
        assertTrue(chosen.origin().y() >= 70 && chosen.origin().y() <= 319 - 9);
        List<PlannedPortalBlock> blocks = chosen.blocks();
        int firstAir = firstIndexOfType(blocks, PlannedPortalBlock.Type.AIR);
        int firstObsidian = firstIndexOfType(blocks, PlannedPortalBlock.Type.OBSIDIAN);
        int firstPortalBlock = firstIndexOfType(blocks, PlannedPortalBlock.Type.NETHER_PORTAL);
        assertEquals(0, firstObsidian, "forced placement starts with the platform");
        assertTrue(firstAir > firstObsidian, "air pocket follows platform rows");
        assertTrue(firstPortalBlock > lastIndexOfType(blocks, PlannedPortalBlock.Type.OBSIDIAN));
        for (PlannedPortalBlock block : blocks) {
            NetherPortalBuild.Placement placement = NetherPortalBuild.placementOf(block, chosen.orientation());
            if (placement.isPortal()) {
                assertEquals(Axis.X, placement.axis());
            }
        }
    }

    /** A column scan with no replaceable blocks at all. */
    private static ColumnScan allSolidColumnScan(int bottomY, int topY, int logicalHeight) {
        ColumnScan scan = new ColumnScan(bottomY, topY, logicalHeight);
        int size = topY - bottomY;
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                BitSet replaceable = new BitSet(size);
                BitSet solid = new BitSet(size);
                for (int i = 0; i < size; i++) {
                    solid.set(i);
                }
                scan.put(x, z, topY, replaceable, solid);
            }
        }
        return scan;
    }

    /** Flat void world: solid at y in [0, 64], replaceable air everywhere above. */
    private static ColumnScan flatWorldColumnScan(int bottomY, int topY, int logicalHeight) {
        ColumnScan scan = new ColumnScan(bottomY, topY, logicalHeight);
        int size = topY - bottomY;
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                BitSet replaceable = new BitSet(size);
                BitSet solid = new BitSet(size);
                for (int i = 0; i < size; i++) {
                    int y = bottomY + i;
                    if (y <= 64) {
                        solid.set(i);
                    } else {
                        replaceable.set(i);
                    }
                }
                scan.put(x, z, 65, replaceable, solid);
            }
        }
        return scan;
    }

    private static int firstIndexOfType(List<PlannedPortalBlock> blocks, PlannedPortalBlock.Type type) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfType(List<PlannedPortalBlock> blocks, PlannedPortalBlock.Type type) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (blocks.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }
}

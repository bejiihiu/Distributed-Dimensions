package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCreationPlannerTest {

    private static final WorldBorderBounds BORDER = WorldBorderBounds.DEFAULT_NETHER;
    private static final PortalOrientation X = PortalOrientation.X_AXIS;
    private static final PortalOrientation Z = PortalOrientation.Z_AXIS;

    private static PortalCreationPlan planAt(BlockPos target, PortalOrientation hint, WorldAccess world) {
        return PortalCreationPlanner.plan(target, hint, world, BORDER)
                .orElseThrow(() -> new AssertionError("expected a plan"));
    }

    private static long countType(PortalCreationPlan plan, PlannedPortalBlock.Type type) {
        return plan.blocks().stream().filter(b -> b.type() == type).count();
    }

    @Test
    void flatOverworldGroundAnchorsPortalOnTopOfSurface() {
        // overworld flat: ground -64..63, air above; target above a clear column
        FixtureWorld world = FixtureWorld.overworld().flatGround(63);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertFalse(plan.forcedPlacement());
        assertFalse(plan.obsidianPlatform());
        assertEquals(new BlockPos(0, 64, 0), plan.origin());
        assertEquals(X, plan.orientation());
        // inner rect 2x3 at the origin
        assertEquals(new PortalInnerRect(new BlockPos(0, 64, 0), X, 2, 3), plan.innerRect());
        // placement order: no forced blocks, then frame OBSIDIAN, then NETHER_PORTAL
        List<PlannedPortalBlock> blocks = plan.blocks();
        assertEquals(20, blocks.size());
        assertEquals(14, countType(plan, PlannedPortalBlock.Type.OBSIDIAN));
        assertEquals(6, countType(plan, PlannedPortalBlock.Type.NETHER_PORTAL));
        // frame starts at y = origin - 1, portal blocks at origin..origin+2
        assertEquals(-1, blocks.get(0).at().y() - plan.origin().y());
        // portal blocks occupy the inner 2x3 at the origin
        assertTrue(blocks.stream().filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .allMatch(b -> plan.innerRect().contains(b.at())));
    }

    @Test
    void flatNetherGroundWithCeilingAnchorsAtLowestAirRun() {
        // nether: solid floor 0..31, solid ceiling 120..127, air pocket between
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 31, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 120, 127, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        // the scan anchors at the BOTTOM of the air run, i.e. as low as possible
        assertEquals(new BlockPos(0, 32, 0), plan.origin());
        assertFalse(plan.forcedPlacement());
    }

    @Test
    void netherOpenWorldAnchorsAboveHighestBlock() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 31, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 100, 0), X, world);
        assertEquals(new BlockPos(0, 32, 0), plan.origin());
    }

    @Test
    void giantOverhangDoesNotPushPortalUp() {
        // floating slab at y=100..105 over the entire area; portal must still anchor at y=32
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 31, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 100, 105, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertEquals(new BlockPos(0, 32, 0), plan.origin());
    }

    @Test
    void portalAnchorsAtBottomOfDeepAirRunNotItsTop() {
        // column with a deep air shaft: anchors at the bottom (y=64), not at the top (y=100)
        FixtureWorld world = FixtureWorld.overworld()
                .fillBox(-16, 16, -64, 63, -16, 16, FixtureWorld.Kind.SOLID)
                .fillBox(-16, 16, 101, 110, -16, 16, FixtureWorld.Kind.SOLID); // roof above the shaft
        PortalCreationPlan plan = planAt(new BlockPos(0, 80, 0), X, world);
        assertEquals(new BlockPos(0, 64, 0), plan.origin());
    }

    @Test
    void fullFloorNetherAnchorsAboveIt() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 4, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(10, 100, 10), X, world);
        assertEquals(new BlockPos(10, 5, 10), plan.origin());
    }

    @Test
    void minHeightNetherWithOnlyBedrockAtY0AnchorsAboveIt() {
        // bedrock exactly at y=0 and nothing else: lowest possible portal is at y=1
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 0, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(10, 100, 10), X, world);
        assertEquals(new BlockPos(10, 1, 10), plan.origin());
        assertFalse(plan.forcedPlacement());
    }

    @Test
    void waterColumnOverTargetPushesSearchToNeighborAirColumn() {
        // ground 0..59, water 60..70 only above the x=0 axis columns, air 71+
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 59, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(0, 0, 60, 70, 0, 0, FixtureWorld.Kind.WATER)
                .fillBox(0, 0, 60, 70, 2, 2, FixtureWorld.Kind.WATER)
                .fillBox(0, 0, 60, 70, -2, -2, FixtureWorld.Kind.WATER);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertFalse(plan.forcedPlacement());
        // anchors (1,60,0)/(0,60,±2) are out (frame overlaps water / foundation would be water);
        // the nearest valid full site is (2,60,0) with the frame x in [1..4] clear of the water
        assertEquals(new BlockPos(2, 60, 0), plan.origin());
    }

    @Test
    void worldWideWaterForcesForcedPlacementAndOverwrites() {
        // ground 0..59 everywhere, water 60..70 everywhere: no valid site in radius 16
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 59, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 60, 70, -32, 32, FixtureWorld.Kind.WATER);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertTrue(plan.obsidianPlatform());
        // forced Y clamp: [max(bottomY+1, 70), maxPlaceable-9] = [70, 118]; target 64 clamps to 70
        assertEquals(new BlockPos(-1, 70, 0), plan.origin());
        // 10 OBSIDIAN at y=69: 6 platform cells + 4 frame bottom row cells
        long platformObsidian = plan.blocks().stream()
                .filter(b -> b.type() == PlannedPortalBlock.Type.OBSIDIAN && b.at().y() == 69)
                .count();
        assertEquals(10, platformObsidian);
        // air pocket 2x3 x 3 rows above the platform
        long air = countType(plan, PlannedPortalBlock.Type.AIR);
        assertEquals(18, air);
        // platform corner blocks, placed before the frame (the frame only overwrites the same row)
        assertTrue(plan.blocks().stream().anyMatch(b -> b.type() == PlannedPortalBlock.Type.OBSIDIAN
                && b.at().equals(new BlockPos(-1, 69, -1))));
        assertTrue(plan.blocks().stream().anyMatch(b -> b.type() == PlannedPortalBlock.Type.OBSIDIAN
                && b.at().equals(new BlockPos(0, 69, 1))));
        // 20 OBSIDIAN = 6 platform + 14 frame, 6 portal blocks inside
        assertEquals(20, countType(plan, PlannedPortalBlock.Type.OBSIDIAN));
        assertEquals(6, countType(plan, PlannedPortalBlock.Type.NETHER_PORTAL));
        // portal blocks overwrite the pocket: they sit at y=70..72, x=-1..0, z=0
        assertTrue(plan.blocks().stream().filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .allMatch(b -> b.at().z() == 0 && b.at().y() >= 70 && b.at().y() <= 72));
    }

    @Test
    void forcedPlacementClampsHighTargetsDownInNether() {
        // water everywhere, target at y=127: clamped to maxPlaceable-9 = 118
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 59, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 60, 127, -32, 32, FixtureWorld.Kind.WATER);
        PortalCreationPlan plan = planAt(new BlockPos(0, 127, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(118, plan.origin().y());
    }

    @Test
    void forcedPlacementClampsLowTargetsUpInOverworld() {
        // water everywhere, target y=5: clamped to 70
        FixtureWorld world = FixtureWorld.overworld()
                .fillBox(-32, 32, -64, 31, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 32, 320, -32, 32, FixtureWorld.Kind.WATER);
        PortalCreationPlan plan = planAt(new BlockPos(0, 5, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(70, plan.origin().y());
    }

    @Test
    void forcedPlacementClampsHighTargetsDownInOverworld() {
        // target y=320: clamped to maxPlaceable-9 = 310
        FixtureWorld world = FixtureWorld.overworld()
                .fillBox(-32, 32, -64, 100, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 101, 320, -32, 32, FixtureWorld.Kind.WATER);
        PortalCreationPlan plan = planAt(new BlockPos(0, 320, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(310, plan.origin().y());
    }

    @Test
    void bottomlessVoidOverworldForcesPlacement() {
        FixtureWorld world = FixtureWorld.overworld(); // entirely air
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(new BlockPos(-1, 70, 0), plan.origin());
    }

    @Test
    void bottomlessVoidNetherForcesPlacementAtY70() {
        FixtureWorld world = FixtureWorld.nether(); // entirely air
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(new BlockPos(-1, 70, 0), plan.origin());
    }

    @Test
    void floatingIslandGetsItsPortal() {
        // no ground anywhere; one island at x,z in [6..12], y 40..43; open nether
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(6, 12, 40, 43, 6, 12, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertFalse(plan.forcedPlacement());
        // anchors are anchored at the bottom of the air run: first free row above the island slab
        assertEquals(44, plan.origin().y());
        // nearest full site: side planes must be free and the frame x in [6..9] inside the island;
        // (6,44,7) fails because its frame extends to x=5, outside the island slab
        assertEquals(new BlockPos(7, 44, 7), plan.origin());
    }

    @Test
    void borderEdgeColumnAndNextColumnRule() {
        // anchor (29,*,29) fails because column+dir (30,*,29) is outside;
        // anchor (28,64,29) is valid (both 28 and 29 inside the border)
        WorldBorderBounds border = new WorldBorderBounds(-10, -10, 30, 30);
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = PortalCreationPlanner.plan(new BlockPos(29, 64, 29), X, world, border)
                .orElseThrow();
        assertFalse(plan.forcedPlacement());
        assertEquals(new BlockPos(28, 64, 29), plan.origin());
    }

    @Test
    void farOutsideBorderForcedPlacementClampsIntoIt() {
        // everything beyond a small border is unreachable for the search; forced placement clamps
        WorldBorderBounds border = new WorldBorderBounds(-10, -10, 10, 10);
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = PortalCreationPlanner.plan(new BlockPos(100, 64, 100), X, world, border)
                .orElseThrow();
        assertTrue(plan.forcedPlacement());
        // spot = target - direction (EAST) border-clamped: clamp(99) -> 9 -> spot x=9
        assertEquals(new BlockPos(9, 70, 9), plan.origin());
    }

    @Test
    void partialSiteIsChosenWhenNoFullExistsAnywhere() {
        // solid roof y=64..127 everywhere; air only along the axis line z==0 -> only partial sites
        FixtureWorld roofed = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 64, 127, -32, 32, FixtureWorld.Kind.SOLID);
        // carve the axis line z == 0 out of the roof
        for (int x = -32; x <= 32; x++) {
            for (int y = 64; y <= 127; y++) {
                roofed.set(x, y, 0, FixtureWorld.Kind.AIR);
            }
        }
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, roofed);
        assertFalse(plan.forcedPlacement());
        // only partial sites are possible; the nearest is the target column itself
        assertEquals(new BlockPos(0, 64, 0), plan.origin());
    }

    @Test
    void fullSiteBeatsNearerPartialSite() {
        // same roof, but a small air pocket x in [8..17], z == +-1, y 64..67 lets anchor (9,64,0) go full
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 64, 127, -32, 32, FixtureWorld.Kind.SOLID);
        for (int x = -32; x <= 32; x++) {
            for (int y = 64; y <= 127; y++) {
                world.set(x, y, 0, FixtureWorld.Kind.AIR);
            }
        }
        for (int x = 8; x <= 17; x++) {
            for (int y = 64; y <= 67; y++) {
                world.set(x, y, 1, FixtureWorld.Kind.AIR);
                world.set(x, y, -1, FixtureWorld.Kind.AIR);
            }
        }
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertFalse(plan.forcedPlacement());
        // nearest FULL site is (9,64,0); the nearest partial (0,64,0) must lose to it
        assertEquals(new BlockPos(9, 64, 0), plan.origin());
    }

    @Test
    void obstructedAxisForcesPlacementEvenWithRoof() {
        // roof everywhere and the axis line is ALSO solid -> nothing valid, forced placement wins
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(-32, 32, 64, 127, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertTrue(plan.forcedPlacement());
        assertEquals(new BlockPos(-1, 70, 0), plan.origin());
    }

    @Test
    void obstructedTargetColumnYieldsNearestValidNeighbor() {
        // solid roof in col (0,*,0): no site there either, and anchor (1,64,0) is invalid too
        // because its frame extends to x=0 (roofed rows 64..67) — nearest valid full site is (2,64,0)
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID)
                .fillBox(0, 0, 64, 127, 0, 0, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertEquals(new BlockPos(2, 64, 0), plan.origin());
        assertFalse(plan.forcedPlacement());
    }

    @Test
    void orientationHintZBuildsPortalAlongZ() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), Z, world);
        assertEquals(Z, plan.orientation());
        // inner rect spans Z
        assertEquals(new PortalInnerRect(new BlockPos(0, 64, 0), Z, 2, 3), plan.innerRect());
        // frame lower-left sits one block below and one block before the inner rect along Z
        assertEquals(new BlockPos(0, 63, -1), plan.frameLowerLeft());
        // portal blocks at (0, 64..66, 0..1)
        assertTrue(plan.blocks().stream().filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .allMatch(b -> b.at().x() == 0 && b.at().z() >= 0 && b.at().z() <= 1));
    }

    @Test
    void orientationHintXBuildsPortalAlongX() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 63, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        assertEquals(new BlockPos(-1, 63, 0), plan.frameLowerLeft());
        assertTrue(plan.blocks().stream().filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .allMatch(b -> b.at().z() == 0 && b.at().x() >= 0 && b.at().x() <= 1));
    }

    @Test
    void forcedPlacementHonorsZAxisHint() {
        FixtureWorld world = FixtureWorld.nether(); // entirely air
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), Z, world);
        assertTrue(plan.forcedPlacement());
        // spot = target - SOUTH = (0, 70, -1)
        assertEquals(new BlockPos(0, 70, -1), plan.origin());
        assertEquals(Z, plan.orientation());
    }

    @Test
    void tooShallowDimensionCannotForcePlacement() {
        // topY 75 -> forced Y range [70, 66] is empty -> empty result
        FixtureWorld world = new FixtureWorld(0, 75, 75);
        Optional<PortalCreationPlan> plan = PortalCreationPlanner.plan(new BlockPos(0, 50, 0), X, world, BORDER);
        assertTrue(plan.isEmpty());
    }

    @Test
    void planIsDeterministic() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 31, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan first = planAt(new BlockPos(3, 64, 5), X, world);
        PortalCreationPlan second = planAt(new BlockPos(3, 64, 5), X, world);
        assertEquals(first.blocks(), second.blocks());
        assertEquals(first.origin(), second.origin());
    }

    @Test
    void nearestFullSiteWinsOverFartherFullSite() {
        // all-air island strip: two full sites, (10,44,10) and (20,44,20); nearest wins
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(8, 13, 40, 43, 8, 13, FixtureWorld.Kind.SOLID)
                .fillBox(18, 23, 40, 43, 18, 23, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = planAt(new BlockPos(0, 64, 0), X, world);
        // anchor (6,·,7) fails: its frame extends to x=5, outside the island
        // anchor (9,·,9) is the nearest full site: frame x in [8..11], side planes z=8 and z=10
        assertEquals(new BlockPos(9, 44, 9), plan.origin());
    }

    @Test
    void nullArgumentsRejected() {
        FixtureWorld world = FixtureWorld.nether();
        assertThrows(NullPointerException.class, () -> PortalCreationPlanner.plan(null, X, world, BORDER));
        assertThrows(NullPointerException.class, () -> PortalCreationPlanner.plan(new BlockPos(0, 64, 0), null, world, BORDER));
        assertThrows(NullPointerException.class, () -> PortalCreationPlanner.plan(new BlockPos(0, 64, 0), X, null, BORDER));
        assertThrows(NullPointerException.class, () -> PortalCreationPlanner.plan(new BlockPos(0, 64, 0), X, world, null));
    }
}

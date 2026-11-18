package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portal produced by the creation planner must be findable by the search — the blocks the
 * plan would place are exactly the blocks the search consumes.
 */
class PortalPlanSearchRoundTripTest {

    private static final WorldBorderBounds BORDER = WorldBorderBounds.DEFAULT_NETHER;

    @Test
    void plannedPortalIsFoundBySearchWithSameFrame() {
        FixtureWorld world = FixtureWorld.nether()
                .fillBox(-32, 32, 0, 31, -32, 32, FixtureWorld.Kind.SOLID);
        PortalCreationPlan plan = PortalCreationPlanner.plan(new BlockPos(0, 64, 0), PortalOrientation.X_AXIS, world, BORDER)
                .orElseThrow();

        List<BlockPos> portalBlocks = plan.blocks().stream()
                .filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .map(PlannedPortalBlock::at)
                .collect(Collectors.toList());

        PortalSearchResult result = PortalSearch.select(
                        new BlockPos(0, 64, 0), () -> portalBlocks, NetherPortalConstants.NETHER_DESTINATION_SEARCH_RADIUS, BORDER)
                .orElseThrow();

        assertEquals(plan.innerRect(), result.innerRectangle());
        assertEquals(plan.orientation(), result.candidate().orientation());
        assertEquals(plan.origin(), result.innerRectangle().lowerLeft());
    }

    @Test
    void forcedPlanIsAlsoFoundBySearch() {
        FixtureWorld world = FixtureWorld.nether(); // entirely air -> forced placement
        PortalCreationPlan plan = PortalCreationPlanner.plan(new BlockPos(0, 64, 0), PortalOrientation.X_AXIS, world, BORDER)
                .orElseThrow();
        assertTrue(plan.forcedPlacement());

        List<BlockPos> portalBlocks = plan.blocks().stream()
                .filter(b -> b.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                .map(PlannedPortalBlock::at)
                .collect(Collectors.toList());

        PortalSearchResult result = PortalSearch.select(
                        new BlockPos(-1, 70, 0), () -> portalBlocks, NetherPortalConstants.NETHER_DESTINATION_SEARCH_RADIUS, BORDER)
                .orElseThrow();
        assertEquals(plan.innerRect(), result.innerRectangle());
    }
}

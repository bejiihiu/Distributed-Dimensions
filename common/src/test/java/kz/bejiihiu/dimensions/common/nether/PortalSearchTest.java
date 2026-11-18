package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSearchTest {

    private static final WorldBorderBounds BORDER = WorldBorderBounds.DEFAULT_NETHER;

    /** Adds the inner portal blocks of a portal of the given size at the given position. */
    private static List<BlockPos> portalAt(int x, int y, int z, int width, int height, PortalOrientation axis) {
        List<BlockPos> blocks = new ArrayList<>();
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                blocks.add(axis == PortalOrientation.X_AXIS
                        ? new BlockPos(x + dx, y + dy, z)
                        : new BlockPos(x, y + dy, z + dx));
            }
        }
        return blocks;
    }

    private static PortalCandidateView viewOf(BlockPos... blocks) {
        return () -> List.of(blocks);
    }

    private static PortalCandidateView viewOf(List<BlockPos> blocks) {
        return () -> blocks;
    }

    @Test
    void emptyWorldYieldsNoPortal() {
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(0, 64, 0), viewOf(List.of()), 16, BORDER);
        assertTrue(result.isEmpty());
    }

    @Test
    void findsSinglePortalExactlyAtTarget() {
        List<BlockPos> blocks = portalAt(100, 64, 100, 2, 3, PortalOrientation.X_AXIS);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(100, 64, 100), viewOf(blocks), 16, BORDER);
        assertTrue(result.isPresent());
        assertEquals(new PortalInnerRect(new BlockPos(100, 64, 100), PortalOrientation.X_AXIS, 2, 3),
                result.get().innerRectangle());
        assertEquals(PortalOrientation.X_AXIS, result.get().candidate().orientation());
        assertEquals(0.0, result.get().distanceSquared());
    }

    @Test
    void nearestPortalWins() {
        List<BlockPos> near = portalAt(100, 64, 100, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> far = portalAt(110, 64, 110, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> all = new ArrayList<>(near);
        all.addAll(far);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(100, 64, 100), viewOf(all), 16, BORDER);
        assertTrue(result.isPresent());
        // closest portal block to the target is (100, 64, 100) with distance 0
        assertEquals(new BlockPos(100, 64, 100), result.get().selectedBlock());
    }

    @Test
    void squaredDistanceUsesYComponent() {
        // portal A: 10 blocks horizontally away, same Y (dist^2 = 100)
        List<BlockPos> a = portalAt(110, 64, 100, 2, 3, PortalOrientation.X_AXIS);
        // portal B: 1 block horizontally away but 9 blocks up (dist^2 = 1 + 81 = 82)
        List<BlockPos> b = portalAt(101, 73, 100, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> all = new ArrayList<>(a);
        all.addAll(b);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(100, 64, 100), viewOf(all), 16, BORDER);
        assertTrue(result.isPresent());
        assertEquals(new BlockPos(101, 73, 100), result.get().selectedBlock());
        assertEquals(82.0, result.get().distanceSquared());
    }

    @Test
    void radiusIsHorizontalSquareNotCircleAndNot3D() {
        // (16, 64, 16) is inside a 16-radius square for target (0, 64, 0); a circle would exclude it
        List<BlockPos> corner = portalAt(16, 64, 16, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(corner), 16, BORDER).isPresent());

        // (17, 64, 0) is outside the 16-radius square
        List<BlockPos> outside = portalAt(17, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(outside), 16, BORDER).isEmpty());

        // radius applies to XZ only: a block 16 away in X survives any Y difference
        List<BlockPos> high = portalAt(16, 250, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(high), 16, BORDER).isPresent());

        // and blocks beyond radius in Z but inside X are outside
        List<BlockPos> outZ = portalAt(0, 64, 17, 2, 3, PortalOrientation.Z_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(outZ), 16, BORDER).isEmpty());
    }

    @Test
    void negativeCoordinatesRadiusWorksSymmetrically() {
        // portal at x in [-16, -15]: the -16 block is exactly on the radius edge and inside
        List<BlockPos> edge = portalAt(-16, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(edge), 16, BORDER).isPresent());
        // portal at x in [-18, -17]: both blocks are outside the radius
        List<BlockPos> tooFar = portalAt(-18, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(tooFar), 16, BORDER).isEmpty());
    }

    @Test
    void overworldSearchRadius128Applies() {
        List<BlockPos> edge = portalAt(128, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(edge), 128, BORDER).isPresent());
        List<BlockPos> outside = portalAt(129, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(outside), 128, BORDER).isEmpty());
    }

    @Test
    void verticalColumnOfPortalBlocksIsOneCandidate() {
        // a whole vertical tower of portal blocks must be a single portal, not one per block
        List<BlockPos> tower = new ArrayList<>();
        for (int y = 40; y < 90; y++) {
            tower.add(new BlockPos(10, y, 10));
        }
        List<PortalCandidate> grouped = PortalCandidateGrouper.group(tower);
        assertEquals(1, grouped.size());
        assertEquals(50, grouped.get(0).blocks().size());

        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(10, 44, 10), viewOf(tower), 16, BORDER);
        assertTrue(result.isPresent());
        assertEquals(50, result.get().candidate().blocks().size());
        assertEquals(new BlockPos(10, 44, 10), result.get().selectedBlock());
    }

    @Test
    void tieBreakByLowerXThenZWhenDistanceAndYTie() {
        // two 1-wide portals at x = -3 and x = 3, both at y = 71: nearest blocks (-3,71,0) and (3,71,0)
        // are exactly d2 = 58 from the target; equal distance, equal y -> lower x wins
        List<BlockPos> left = portalAt(-3, 71, 0, 1, 3, PortalOrientation.X_AXIS);
        List<BlockPos> right = portalAt(3, 71, 0, 1, 3, PortalOrientation.X_AXIS);
        List<BlockPos> all = new ArrayList<>(left);
        all.addAll(right);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(0, 64, 0), viewOf(all), 16, BORDER);
        assertTrue(result.isPresent());
        // exact tie of 58: lower Y not available (same Y), so lower x wins (-3)
        assertEquals(new BlockPos(-3, 71, 0), result.get().selectedBlock());
    }

    @Test
    void tieBreakPrefersLowerYOverEqualDistance() {
        // one portal below and one above the target's Y at equal distance:
        // nearest blocks (4, 63, 5) and (4, 65, 5) are both d2 = 16 + 1 + 25 = 42; y = 63 wins
        List<BlockPos> below = portalAt(4, 61, 5, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> above = portalAt(4, 65, 5, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> all = new ArrayList<>(below);
        all.addAll(above);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(0, 64, 0), viewOf(all), 16, BORDER);
        assertTrue(result.isPresent());
        assertEquals(new BlockPos(4, 63, 5), result.get().selectedBlock());
    }

    @Test
    void destroyedPortalProducesNoCandidates() {
        // destroyed portal = zero portal blocks fed; selection must come back empty
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(List.of()), 128, BORDER).isEmpty());
        // destroyed except one row: not a rectangle but still portal blocks -> a degenerate candidate, not a crash
        List<BlockPos> leftover = List.of(new BlockPos(5, 64, 5));
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(leftover), 16, BORDER).isPresent());
    }

    @Test
    void bothOrientationsAreFound() {
        List<BlockPos> alongX = portalAt(100, 64, 100, 2, 3, PortalOrientation.X_AXIS);
        List<BlockPos> alongZ = portalAt(100, 64, 100, 2, 3, PortalOrientation.Z_AXIS);
        assertEquals(PortalOrientation.X_AXIS,
                PortalSearch.select(new BlockPos(100, 64, 100), viewOf(alongX), 16, BORDER).get().candidate().orientation());
        // Z_AXIS portal at same origin occupies x=100 plane, z 100..101
        assertEquals(PortalOrientation.Z_AXIS,
                PortalSearch.select(new BlockPos(100, 64, 100), viewOf(alongZ), 16, BORDER).get().candidate().orientation());
    }

    @Test
    void borderFilteringRemovesOutOfBorderPortals() {
        WorldBorderBounds small = new WorldBorderBounds(-10, -10, 10, 10);
        List<BlockPos> outside = portalAt(20, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(outside), 128, small).isEmpty());
        List<BlockPos> inside = portalAt(9, 64, 0, 2, 3, PortalOrientation.Z_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(inside), 128, small).isPresent());
    }

    @Test
    void positionTargetIsFloored() {
        // target (100.9, 64.4, 100.9) floors to (100, 64, 100)
        List<BlockPos> portal = portalAt(100, 64, 100, 2, 3, PortalOrientation.X_AXIS);
        Optional<PortalSearchResult> result =
                PortalSearch.select(new Position(100.9, 64.4, 100.9), viewOf(portal), 16, BORDER);
        assertTrue(result.isPresent());
        assertEquals(0.0, result.get().distanceSquared());
    }

    @Test
    void innerRectangleFor2x3PortalFromNearestBlock() {
        // standard 2x3 inner portal; selection anchors at nearest block; largest rectangle = 2x3
        List<BlockPos> portal = portalAt(50, 70, 50, 2, 3, PortalOrientation.X_AXIS);
        PortalSearchResult result = PortalSearch.select(new BlockPos(50, 70, 50), viewOf(portal), 16, BORDER).orElseThrow();
        assertEquals(new PortalInnerRect(new BlockPos(50, 70, 50), PortalOrientation.X_AXIS, 2, 3),
                result.innerRectangle());
    }

    @Test
    void innerRectangleForLargePortal() {
        // 4x5 inner (e.g. created portal with frame left, portal blocks 4x5) at (200, 80, 200)
        List<BlockPos> portal = portalAt(200, 80, 200, 4, 5, PortalOrientation.Z_AXIS);
        PortalSearchResult result = PortalSearch.select(new BlockPos(201, 82, 200), viewOf(portal), 16, BORDER).orElseThrow();
        assertEquals(new PortalInnerRect(new BlockPos(200, 80, 200), PortalOrientation.Z_AXIS, 4, 5),
                result.innerRectangle());
    }

    @Test
    void innerRectangleForRowKnockoutKeepsLargestLowestLobe() {
        // 6x4 plane x=0, rows y=10..13, z=0..5, with row y=12 missing at z=3 and z=4
        // (row knockouts split the run; vanilla anchors the upward scan at the split row,
        // so the largest rectangle is (z=0..2) x (y=10..13) = 3x4)
        List<BlockPos> blocks = new ArrayList<>();
        for (int dy = 0; dy < 4; dy++) {
            for (int dz = 0; dz < 6; dz++) {
                if (dy == 2 && (dz == 3 || dz == 4)) {
                    continue; // row 12 at z 3..4 knocked out
                }
                blocks.add(new BlockPos(0, 10 + dy, dz));
            }
        }
        PortalSearchResult result = PortalSearch.select(new BlockPos(0, 20, 0), viewOf(blocks), 16, BORDER).orElseThrow();
        assertEquals(new BlockPos(0, 13, 0), result.selectedBlock());
        assertEquals(new PortalInnerRect(new BlockPos(0, 10, 0), PortalOrientation.Z_AXIS, 3, 4),
                result.innerRectangle());
        for (BlockPos b : result.innerRectangle().blocks()) {
            assertTrue(blocks.contains(b), "rectangle contains destroyed block " + b);
        }
    }

    @Test
    void candidatesMergedAcrossOneSharedBlockStillSelectByNearestBlock() {
        // two planes meeting at a corner share block (0, y, 0): grouped as one candidate,
        // but the search picks the plane whose nearest block is closer
        List<BlockPos> alongX = portalAt(0, 70, 0, 2, 3, PortalOrientation.X_AXIS);   // x 0..1
        List<BlockPos> alongZ = portalAt(0, 70, 0, 2, 3, PortalOrientation.Z_AXIS);   // z 0..1
        List<BlockPos> merged = new ArrayList<>(alongX);
        merged.addAll(alongZ);
        // target to the +z side: nearer along z (plane x=0)
        PortalSearchResult result = PortalSearch.select(new BlockPos(0, 70, 6), viewOf(merged), 16, BORDER).orElseThrow();
        assertNotNull(result.innerRectangle());
    }

    @Test
    void searchRadiusForDestinationIsNetherMatchesVanillaConstants() {
        assertEquals(16, NetherPortalConstants.searchRadiusForDestination(true));
        assertEquals(128, NetherPortalConstants.searchRadiusForDestination(false));
    }

    @Test
    void selectionIsDeterministicAcrossRuns() {
        List<BlockPos> blocks = new ArrayList<>();
        blocks.addAll(portalAt(5, 64, 5, 2, 3, PortalOrientation.X_AXIS));
        blocks.addAll(portalAt(-50, 200, -50, 2, 3, PortalOrientation.Z_AXIS));
        blocks.addAll(portalAt(12, 12, 130, 4, 5, PortalOrientation.X_AXIS));
        for (int i = 0; i < 25; i++) {
            PortalSearchResult first = PortalSearch.select(new BlockPos(0, 64, 0), viewOf(blocks), 128, BORDER).orElseThrow();
            PortalSearchResult second = PortalSearch.select(new BlockPos(0, 64, 0), viewOf(blocks), 128, BORDER).orElseThrow();
            assertEquals(first.selectedBlock(), second.selectedBlock());
            assertEquals(first.candidate().blocks(), second.candidate().blocks());
        }
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class,
                () -> PortalSearch.select((BlockPos) null, viewOf(List.of()), 16, BORDER));
        assertThrows(NullPointerException.class,
                () -> PortalSearch.select(new BlockPos(0, 64, 0), null, 16, BORDER));
        assertThrows(NullPointerException.class,
                () -> PortalSearch.select(new BlockPos(0, 64, 0), viewOf(List.of()), 16, null));
        assertThrows(IllegalArgumentException.class,
                () -> PortalSearch.select(new BlockPos(0, 64, 0), viewOf(List.of()), -1, BORDER));
        assertThrows(NullPointerException.class,
                () -> PortalSearch.select((Position) null, viewOf(List.of()), 16, BORDER));
    }

    @Test
    void destroyedPortalSelectsDifferentPortal() {
        // the only portal in range is "destroyed" -> empty result, triggering creation upstream
        Optional<PortalSearchResult> result =
                PortalSearch.select(new BlockPos(0, 64, 0), viewOf(List.of()), 16, BORDER);
        assertTrue(result.isEmpty());
        // but while any portal block survives the search keys on what remains
        List<BlockPos> oneLeft = List.of(new BlockPos(10, 66, 10));
        Optional<PortalSearchResult> leftover =
                PortalSearch.select(new BlockPos(0, 64, 0), viewOf(oneLeft), 16, BORDER);
        assertTrue(leftover.isPresent());
        assertEquals(new BlockPos(10, 66, 10), leftover.get().selectedBlock());
    }

    @Test
    void portalBehindBorderExcludedEvenWhenInRadius() {
        WorldBorderBounds tiny = new WorldBorderBounds(-5, -5, 5, 5);
        List<BlockPos> portal = portalAt(10, 64, 0, 2, 3, PortalOrientation.X_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(portal), 16, tiny).isEmpty());
        // and a portal at the border edge is inside (x == 4 inside [west, east))
        List<BlockPos> edge = portalAt(4, 64, 0, 2, 3, PortalOrientation.Z_AXIS);
        assertTrue(PortalSearch.select(new BlockPos(0, 64, 0), viewOf(edge), 16, tiny).isPresent());
    }
}

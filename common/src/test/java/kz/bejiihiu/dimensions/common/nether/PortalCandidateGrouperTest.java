package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCandidateGrouperTest {

    private static Set<BlockPos> plane(int fixed, boolean xAxis, int yMin, int yMax, int spanMin, int spanMax) {
        Set<BlockPos> blocks = new java.util.HashSet<>();
        for (int y = yMin; y <= yMax; y++) {
            for (int s = spanMin; s <= spanMax; s++) {
                blocks.add(xAxis ? new BlockPos(s, y, fixed) : new BlockPos(fixed, y, s));
            }
        }
        return blocks;
    }

    @Test
    void connectedBlocksFormOneGroup() {
        List<PortalCandidate> groups = PortalCandidateGrouper.group(plane(7, true, 64, 66, 100, 101));
        assertEquals(1, groups.size());
        assertEquals(new BlockPos(100, 64, 7), groups.get(0).lowerLeft());
        assertEquals(new BlockPos(101, 66, 7), groups.get(0).upperRight());
        assertEquals(PortalOrientation.X_AXIS, groups.get(0).orientation());
    }

    @Test
    void disjointPortalsFormSeparateGroups() {
        java.util.Set<BlockPos> all = new java.util.HashSet<>(plane(7, true, 64, 66, 100, 101));
        all.addAll(plane(200, true, 64, 66, 200, 201));
        List<PortalCandidate> groups = PortalCandidateGrouper.group(all);
        assertEquals(2, groups.size());
    }

    @Test
    void duplicatedInputIsDeDuplicated() {
        List<BlockPos> withDupes = List.of(
                new BlockPos(100, 64, 7), new BlockPos(100, 64, 7), new BlockPos(101, 64, 7));
        List<PortalCandidate> groups = PortalCandidateGrouper.group(withDupes);
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).blocks().size());
    }

    @Test
    void verticalTowerIsOneCandidate() {
        List<BlockPos> tower = new java.util.ArrayList<>();
        for (int y = 10; y < 40; y++) {
            tower.add(new BlockPos(5, y, 5));
        }
        List<PortalCandidate> groups = PortalCandidateGrouper.group(tower);
        assertEquals(1, groups.size());
        assertEquals(30, groups.get(0).blocks().size());
    }

    @Test
    void zAxisPortalDetected() {
        List<PortalCandidate> groups = PortalCandidateGrouper.group(plane(4, false, 30, 33, 8, 9));
        assertEquals(1, groups.size());
        assertEquals(PortalOrientation.Z_AXIS, groups.get(0).orientation());
    }

    @Test
    void memberLookupWorks() {
        List<PortalCandidate> groups = PortalCandidateGrouper.group(plane(7, true, 64, 66, 100, 101));
        assertTrue(groups.get(0).contains(new BlockPos(101, 66, 7)));
        assertTrue(!groups.get(0).contains(new BlockPos(102, 64, 7)));
    }

    @Test
    void outputIsDeterministic() {
        java.util.Set<BlockPos> all = new java.util.HashSet<>(plane(7, true, 64, 66, 100, 101));
        all.addAll(plane(300, false, 64, 66, 300, 301));
        List<PortalCandidate> first = PortalCandidateGrouper.group(all);
        List<PortalCandidate> second = PortalCandidateGrouper.group(all);
        assertEquals(first, second);
    }

    @Test
    void nonPlanarClusterResolvedDeterministically() {
        // an L-shape in two planes sharing one block: deterministic thin-plane orientation, X_AXIS wins ties
        java.util.Set<BlockPos> blocks = new java.util.HashSet<>();
        for (int x = 0; x <= 2; x++) {
            blocks.add(new BlockPos(x, 10, 0));
        }
        blocks.add(new BlockPos(0, 11, 0));
        blocks.add(new BlockPos(0, 12, 0));
        blocks.add(new BlockPos(0, 10, 1));
        blocks.add(new BlockPos(0, 10, 2));
        List<PortalCandidate> groups = PortalCandidateGrouper.group(blocks);
        assertEquals(1, groups.size());
        // distinct x values = 3, distinct z values = 3 -> tie -> X_AXIS
        assertEquals(PortalOrientation.X_AXIS, groups.get(0).orientation());
    }
}

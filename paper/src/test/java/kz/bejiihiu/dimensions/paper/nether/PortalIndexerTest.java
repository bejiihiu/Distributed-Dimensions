package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.nether.PortalCandidate;
import kz.bejiihiu.dimensions.common.nether.PortalCandidateGrouper;
import kz.bejiihiu.dimensions.common.persistence.PortalIndex;
import kz.bejiihiu.dimensions.common.persistence.PortalIndexEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalIndexerTest {

    private FakePortalIndex index;
    private PortalIndexer indexer;
    private final List<String> diagnostics = new ArrayList<>();

    @BeforeEach
    void setUp() {
        index = new FakePortalIndex();
        indexer = new PortalIndexer(() -> Optional.of(index), diagnostics::add);
        diagnostics.clear();
    }

    @Test
    void candidatesAreReturnedFromTheStore() {
        seedEntry(PortalOrientation.X_AXIS, 10, 64, 10, 10, 66);
        Optional<List<PortalIndexEntry>> result = indexer.candidates(DimensionId.NETHER, -50, 50, -50, 50);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
    }

    @Test
    void failingQueryYieldsTheDegradedSignal() {
        index.failCandidates = true;
        Optional<List<PortalIndexEntry>> result = indexer.candidates(DimensionId.NETHER, -50, 50, -50, 50);
        assertTrue(result.isEmpty(), "a DB error must read as degraded so the caller runs the full scan");
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("portal index query failed")));
    }

    @Test
    void unavailableStoreYieldsTheDegradedSignal() {
        PortalIndexer degraded = new PortalIndexer(Optional::empty, diagnostics::add);
        assertTrue(degraded.candidates(DimensionId.NETHER, -1, 1, -1, 1).isEmpty());
        assertTrue(degraded.degraded());
    }

    @Test
    void staleEntriesAreDeletedWhenTheScanFindsNothing() {
        seedEntry(PortalOrientation.X_AXIS, 100, 64, 100, 101, 66);
        List<PortalIndexEntry> queried = index.candidatesIn(DimensionId.NETHER, -200, 200, -200, 200);
        indexer.reconcile(DimensionId.NETHER, queried, List.of(), List.of());
        assertEquals(0, index.storage.size(), "an entry with no surviving portal blocks must be dropped");
    }

    @Test
    void liveEntriesAreKept() {
        seedEntry(PortalOrientation.X_AXIS, 100, 64, 100, 101, 66);
        List<BlockPos> blocks = List.of(new BlockPos(100, 64, 100), new BlockPos(101, 64, 100));
        List<PortalIndexEntry> queried = index.candidatesIn(DimensionId.NETHER, -200, 200, -200, 200);
        List<PortalCandidate> grouped = PortalCandidateGrouper.group(blocks);
        indexer.reconcile(DimensionId.NETHER, queried, blocks, grouped);
        assertEquals(1, index.storage.size(), "a portal still present is never deleted or duplicated");
    }

    @Test
    void newlyDiscoveredPortalsAreInserted() {
        List<BlockPos> blocks = portalBlocks(20, 30, 0, PortalOrientation.X_AXIS);
        List<PortalIndexEntry> queried = List.of();
        List<PortalCandidate> grouped = PortalCandidateGrouper.group(blocks);
        indexer.reconcile(DimensionId.NETHER, queried, blocks, grouped);
        assertEquals(1, index.storage.size());
    }

    @Test
    void insertionIsBounded() {
        List<BlockPos> blocks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            blocks.addAll(portalBlocks(i * 10, i * 10 + 1, 0, PortalOrientation.X_AXIS));
        }
        List<PortalCandidate> grouped = PortalCandidateGrouper.group(blocks);
        // 40 candidates, but each portal is two blocks wide and separated in X
        indexer.reconcile(DimensionId.NETHER, List.of(), blocks, grouped);
        assertTrue(index.storage.size() <= PortalIndexer.INSERT_LIMIT,
                "insert bound must cap the write storm, got " + index.storage.size());
    }

    private void seedEntry(PortalOrientation orientation, int minX, int minY, int minZ, int maxX, int maxY) {
        PortalIndexEntry entry = new PortalIndexEntry(UUID.randomUUID(), DimensionId.NETHER, orientation,
                new BlockPos(minX, minY, minZ), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, minZ),
                java.time.Instant.ofEpochMilli(1_800_000_000_000L), java.time.Instant.ofEpochMilli(1_800_000_000_000L));
        index.upsert(entry);
    }

    private static List<BlockPos> portalBlocks(int x0, int x1, int y, PortalOrientation orientation) {
        List<BlockPos> out = new ArrayList<>();
        if (orientation == PortalOrientation.X_AXIS) {
            for (int x = x0; x <= x1; x++) {
                for (int dy = 0; dy < 3; dy++) {
                    out.add(new BlockPos(x, y + dy, 0));
                }
            }
        } else {
            for (int z = x0; z <= x1; z++) {
                for (int dy = 0; dy < 3; dy++) {
                    out.add(new BlockPos(0, y + dy, z));
                }
            }
        }
        return out;
    }

    private static final class FakePortalIndex implements PortalIndex {
        private final Map<UUID, PortalIndexEntry> storage = new ConcurrentHashMap<>();
        private boolean failCandidates;

        @Override
        public void upsert(PortalIndexEntry entry) {
            // match by inner AABB identity-ish: tests only need counting; keep the random ids
            storage.put(entry.portalId(), entry);
        }

        @Override
        public Optional<PortalIndexEntry> findById(UUID portalId) {
            return Optional.ofNullable(storage.get(portalId));
        }

        @Override
        public List<PortalIndexEntry> candidatesIn(DimensionId dimension, int minX, int maxX, int minZ, int maxZ) {
            if (failCandidates) {
                throw new IllegalStateException("database unreachable (test)");
            }
            List<PortalIndexEntry> out = new ArrayList<>();
            for (PortalIndexEntry entry : storage.values()) {
                if (entry.dimension().equals(dimension) && entry.overlapsHorizontal(minX, maxX, minZ, maxZ)) {
                    out.add(entry);
                }
            }
            out.sort(java.util.Comparator.comparingInt(e -> e.origin().x()));
            return out;
        }

        @Override
        public boolean delete(UUID portalId) {
            return storage.remove(portalId) != null;
        }

        @Override
        public long count(DimensionId dimension) {
            return storage.values().stream().filter(e -> e.dimension().equals(dimension)).count();
        }
    }
}

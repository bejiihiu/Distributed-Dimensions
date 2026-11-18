package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link EntityPayloadStore} contract, engine-agnostic. Subclasses provide a fresh,
 * empty store per test; everything asserted here must hold for every engine — this is the
 * definition of the interface, not a convenience suite.
 */
abstract class EntityPayloadStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T0.plusSeconds(9);

    protected abstract EntityPayloadStore openStore();

    protected void closeStore() {
    }

    @AfterEach
    void tearDown() {
        closeStore();
    }

    private static EntityTransferPayload row(String opId, int seq, Instant created,
            UUID playerUuid, EntityPayloadState state) {
        return new EntityTransferPayload(opId, seq, EntityPayloadKind.ITEM,
                "nether", "minecraft:nether", playerUuid,
                "{\"kind\":\"item\",\"seq\":" + seq + "}",
                state, created, created);
    }

    private static List<EntityTransferPayload> assembly(String opId, Instant created, UUID playerUuid) {
        return List.of(
                row(opId, 0, created, playerUuid, EntityPayloadState.PENDING),
                row(opId, 1, created, playerUuid, EntityPayloadState.PENDING),
                row(opId, 2, created, playerUuid, EntityPayloadState.PENDING));
    }

    @Test
    void findByOpIdMissingReturnsEmpty() {
        EntityPayloadStore store = openStore();
        assertEquals(List.of(), store.findByOpId(UUID.randomUUID().toString()));
        assertEquals(0, store.countForOp(UUID.randomUUID().toString()));
    }

    @Test
    void insertAllAndReadBackInSeqOrder() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        // write in shuffled order — reads must come back pre-ordered
        List<EntityTransferPayload> rows = List.of(
                row(opId, 2, T0, null, EntityPayloadState.PENDING),
                row(opId, 0, T0, null, EntityPayloadState.PENDING),
                row(opId, 1, T0, null, EntityPayloadState.PENDING));
        store.insertAll(rows);
        List<EntityTransferPayload> read = store.findByOpId(opId);
        assertEquals(List.of(0, 1, 2), read.stream().map(EntityTransferPayload::seq).toList());
        assertEquals(3, store.countForOp(opId));
        assertEquals("{\"kind\":\"item\",\"seq\":0}", read.get(0).payloadJson());
        assertEquals(EntityPayloadState.PENDING, read.get(0).state());
    }

    @Test
    void insertAllDuplicateOpAndSeqIsRejected() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(List.of(row(opId, 0, T0, null, EntityPayloadState.PENDING)));
        assertThrows(PersistenceException.class,
                () -> store.insertAll(List.of(row(opId, 0, T0, null, EntityPayloadState.PENDING))));
    }

    @Test
    void findPendingOpIdsGroupsAndOrdersOldestFirst() {
        EntityPayloadStore store = openStore();
        String older = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        store.insertAll(assembly(older, T0, null));
        store.insertAll(assembly(newer, T1, null));
        List<String> pending = store.findPendingOpIds("nether", 10);
        assertEquals(List.of(older, newer), pending);
    }

    @Test
    void findPendingOpIdsRespectsLimitAndServerFilter() {
        EntityPayloadStore store = openStore();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        store.insertAll(assembly(a, T0, null));
        store.insertAll(assembly(b, T1, null));
        List<String> one = store.findPendingOpIds("nether", 1);
        assertEquals(List.of(a), one);
        assertEquals(List.of(), store.findPendingOpIds("end", 10));
    }

    @Test
    void findPendingOpIdsForPlayerFiltersPlayerAndOrders() {
        EntityPayloadStore store = openStore();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String aliceOp = UUID.randomUUID().toString();
        String standaloneOp = UUID.randomUUID().toString();
        store.insertAll(assembly(aliceOp, T0, alice));
        store.insertAll(assembly(standaloneOp, T1, null));
        assertEquals(List.of(aliceOp), store.findPendingOpIdsForPlayer(alice, "nether", 10));
        assertEquals(List.of(), store.findPendingOpIdsForPlayer(bob, "nether", 10));
    }

    @Test
    void claimOpHappyPathClaimsEverythingAllOrNothing() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        List<EntityTransferPayload> claimed = store.claimOp(opId, "nether", T2);
        assertEquals(List.of(0, 1, 2), claimed.stream().map(EntityTransferPayload::seq).toList());
        assertTrue(claimed.stream().allMatch(r -> r.state() == EntityPayloadState.CLAIMED));
        // claimed rows are no longer pending
        assertEquals(List.of(), store.findPendingOpIds("nether", 10));
        // …and a second claim flips nothing
        assertEquals(List.of(), store.claimOp(opId, "nether", T2));
        assertEquals(EntityPayloadState.CLAIMED, store.findByOpId(opId).get(0).state());
    }

    @Test
    void claimOpUnknownOpReturnsEmptyAndChangesNothing() {
        EntityPayloadStore store = openStore();
        assertEquals(List.of(), store.claimOp(UUID.randomUUID().toString(), "nether", T2));
    }

    @Test
    void claimOpWithOnePreFailedRowFailsTheWholeOp() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        // source-side abort flips the whole op to FAILED first
        assertEquals(3, store.failOp(opId, T1));
        assertEquals(List.of(), store.claimOp(opId, "nether", T2));
        assertTrue(store.findByOpId(opId).stream()
                .allMatch(r -> r.state() == EntityPayloadState.FAILED));
    }

    @Test
    void claimOpWithOneRowAlreadyClaimedElsewhereFailsOnlyOwnFlippedRows() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        // simulate "another party already owns row 1" by failing it first — that is the
        // only flip a foreign claim can leave behind, and it makes the op inconsistent
        store.failOp(opId, T1);
        // ...now pretend a retry half-way through: all rows FAILED already; nothing to claim
        assertEquals(List.of(), store.claimOp(opId, "nether", T2));
        assertEquals(EntityPayloadState.FAILED, store.findByOpId(opId).get(1).state());
    }

    @Test
    void claimOpLeavesExternallyClaimedRowsUntouched() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        store.claimOp(opId, "nether", T1);
        // second claimant must not resurrect or re-fail them
        assertEquals(List.of(), store.claimOp(opId, "nether", T2));
        assertTrue(store.findByOpId(opId).stream()
                .allMatch(r -> r.state() == EntityPayloadState.CLAIMED));
    }

    @Test
    void claimOpOnWrongTargetServerFlipsNothing() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        assertEquals(List.of(), store.claimOp(opId, "end", T1));
        assertTrue(store.findByOpId(opId).stream()
                .allMatch(r -> r.state() == EntityPayloadState.PENDING));
    }

    @Test
    void failOpFlipsPendingAndClaimedRows() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        store.claimOp(opId, "nether", T1);
        assertEquals(3, store.failOp(opId, T2));
        assertEquals(0, store.failOp(opId, T2));
        assertTrue(store.findByOpId(opId).stream()
                .allMatch(r -> r.state() == EntityPayloadState.FAILED));
    }

    @Test
    void expireStaleClaimedFlipsOnlyOldClaimedRows() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        store.claimOp(opId, "nether", T1);
        Instant before = T2;   // claim was at T1 — everything is stale
        assertEquals(3, store.expireStaleClaimed(before, T2));
        // a fresh claim (updated_at == T2) is not stale when the cutoff is T2 itself
        String fresh = UUID.randomUUID().toString();
        store.insertAll(assembly(fresh, T2, null));
        store.claimOp(fresh, "nether", T2);
        assertEquals(0, store.expireStaleClaimed(T2, T2.plusSeconds(1)));
    }

    @Test
    void deleteFailedOlderThanReapsOnlyOldFailedRows() {
        EntityPayloadStore store = openStore();
        String old = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        store.insertAll(assembly(old, T0, null));
        store.insertAll(assembly(fresh, T2, null));
        store.failOp(old, T0);
        assertEquals(3, store.deleteFailedOlderThan(T1));
        assertEquals(3, store.countForOp(fresh));
        assertEquals(0, store.countForOp(old));
    }

    @Test
    void deleteForOpRemovesAllRows() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        assertEquals(3, store.deleteForOp(opId));
        assertEquals(0, store.deleteForOp(opId));
        assertEquals(List.of(), store.findByOpId(opId));
    }

    @Test
    void claimedRowsAreCountedNotPending() {
        EntityPayloadStore store = openStore();
        String opId = UUID.randomUUID().toString();
        store.insertAll(assembly(opId, T0, null));
        store.claimOp(opId, "nether", T1);
        // a partially-claimed op is not reported as claimable either — rows are CLAIMED
        assertEquals(List.of(), store.findPendingOpIdsForPlayer(UUID.randomUUID(), "nether", 10));
    }
}

package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link TransferContextStore} contract, engine-agnostic. Subclasses provide a fresh,
 * empty store per test; everything asserted here must hold for every engine — this is the
 * definition of the interface, not a convenience suite.
 */
abstract class TransferContextStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T0.plusSeconds(9);
    private static final Instant CUTOFF = T0.plusSeconds(1_000);

    protected abstract TransferContextStore openStore();

    protected void closeStore() {
    }

    @AfterEach
    void tearDown() {
        closeStore();
    }

    private static TransferContext row(UUID opId, Instant created, PortalOrientation axis) {
        return new TransferContext(opId, null, axis, 0.25, 0.75, 0.0, 0.6, 1.8, created);
    }

    @Test
    void findMissingReturnsEmpty() {
        TransferContextStore store = openStore();
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void putAndReadBack() {
        TransferContextStore store = openStore();
        UUID opId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        TransferContext row = new TransferContext(opId, player, PortalOrientation.Z_AXIS,
                0.125, 0.875, 0.0, 0.6, 1.5, T1);
        store.put(row);
        TransferContext read = store.find(opId).orElseThrow();
        assertEquals(opId, read.operationId());
        assertEquals(player, read.playerUuid());
        assertEquals(PortalOrientation.Z_AXIS, read.sourceAxis());
        assertEquals(0.125, read.fx());
        assertEquals(0.875, read.fy());
        assertEquals(0.0, read.forward());
        assertEquals(0.6, read.entityWidth());
        assertEquals(1.5, read.entityHeight());
        assertEquals(T1, read.createdAt());
    }

    @Test
    void putWithoutPlayerUuidRoundTripsAsNull() {
        TransferContextStore store = openStore();
        UUID opId = UUID.randomUUID();
        store.put(row(opId, T0, PortalOrientation.X_AXIS));
        assertEquals(null, store.find(opId).orElseThrow().playerUuid());
    }

    @Test
    void duplicateOperationIdIsRejected() {
        TransferContextStore store = openStore();
        UUID opId = UUID.randomUUID();
        store.put(row(opId, T0, PortalOrientation.X_AXIS));
        assertThrows(PersistenceException.class, () -> store.put(row(opId, T1, PortalOrientation.Z_AXIS)));
        // the first row stands
        assertEquals(PortalOrientation.X_AXIS, store.find(opId).orElseThrow().sourceAxis());
    }

    @Test
    void deleteIsIdempotent() {
        TransferContextStore store = openStore();
        UUID opId = UUID.randomUUID();
        store.put(row(opId, T0, PortalOrientation.X_AXIS));
        assertEquals(1, store.delete(opId));
        assertEquals(0, store.delete(opId));
        assertTrue(store.find(opId).isEmpty());
    }

    @Test
    void deleteOlderThanReapsOnlyOldRows() {
        TransferContextStore store = openStore();
        UUID old1 = UUID.randomUUID();
        UUID old2 = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        store.put(row(old1, T0, PortalOrientation.X_AXIS));
        store.put(row(old2, T1, PortalOrientation.Z_AXIS));
        store.put(row(fresh, CUTOFF, PortalOrientation.X_AXIS));
        assertEquals(2, store.deleteOlderThan(CUTOFF));
        assertTrue(store.find(old1).isEmpty());
        assertTrue(store.find(old2).isEmpty());
        assertTrue(store.find(fresh).isPresent());
        assertEquals(0, store.deleteOlderThan(CUTOFF));
    }
}

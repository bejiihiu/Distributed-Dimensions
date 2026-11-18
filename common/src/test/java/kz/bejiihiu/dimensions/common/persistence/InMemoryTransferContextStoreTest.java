package kz.bejiihiu.dimensions.common.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The in-memory engine of the transfer-context contract. */
class InMemoryTransferContextStoreTest extends TransferContextStoreContractTest {

    private InMemoryTransferContextStore store;

    @Override
    protected TransferContextStore openStore() {
        store = new InMemoryTransferContextStore();
        return store;
    }

    @Override
    protected void closeStore() {
        store = null;
    }

    @Test
    void sizeAccounting() {
        store = new InMemoryTransferContextStore();
        assertEquals(0, store.size());
        UUID opId = UUID.randomUUID();
        store.put(new kz.bejiihiu.dimensions.common.model.TransferContext(
                opId, null, kz.bejiihiu.dimensions.common.model.PortalOrientation.X_AXIS,
                0.5, 0.5, 0.0, 0.6, 1.8, Instant.ofEpochMilli(1_800_000_000_000L)));
        assertEquals(1, store.size());
        store.delete(opId);
        assertEquals(0, store.size());
    }
}

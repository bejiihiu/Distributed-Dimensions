package kz.bejiihiu.dimensions.common.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The in-memory engine of the payload-store contract. */
class InMemoryEntityPayloadStoreTest extends EntityPayloadStoreContractTest {

    private InMemoryEntityPayloadStore store;

    @Override
    protected EntityPayloadStore openStore() {
        store = new InMemoryEntityPayloadStore();
        return store;
    }

    @Override
    protected void closeStore() {
        store = null;
    }

    @Test
    void sizeAccounting() {
        store = new InMemoryEntityPayloadStore();
        assertEquals(0, store.size());
        String opId = UUID.randomUUID().toString();
        Instant now = Instant.ofEpochMilli(1_800_000_000_000L);
        store.insertAll(java.util.List.of(
                new kz.bejiihiu.dimensions.common.model.EntityTransferPayload(
                        opId, 0, kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MOB,
                        "nether", "minecraft:nether", null,
                        "{}", kz.bejiihiu.dimensions.common.model.EntityPayloadState.PENDING, now, now)));
        assertEquals(1, store.size());
    }
}

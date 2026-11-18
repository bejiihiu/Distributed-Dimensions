package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MongoDB engine of the transfer-context contract. Runs only when {@code TEST_MONGODB_URI}
 * is set; each test gets a unique scratch database that is dropped afterwards.
 */
class MongoTransferContextStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);

    private DimensionPersistence persistence;
    private String database;

    @BeforeEach
    void setUp() {
        String uri = System.getenv("TEST_MONGODB_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(),
                "TEST_MONGODB_URI not set - skipping MongoDB transfer-context tests");
        database = "dd_contexts_" + UUID.randomUUID().toString().replace('-', '_');
        persistence = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.MONGODB)
                        .mongoUri(uri)
                        .mongoDatabase(database)
                        .maximumPoolSize(4)
                        .minimumIdle(1)
                        .build());
    }

    @AfterEach
    void tearDown() {
        if (persistence != null) {
            persistence.close();
            persistence = null;
        }
        String uri = System.getenv("TEST_MONGODB_URI");
        if (uri != null && !uri.isBlank() && database != null) {
            try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(uri)) {
                client.getDatabase(database).drop();
            }
        }
    }

    private static TransferContext row(UUID opId, Instant created, PortalOrientation axis) {
        return new TransferContext(opId, null, axis, 0.5, 0.5, 0.0, 0.6, 1.8, created);
    }

    @Test
    void putFindDeleteRoundTrip() {
        TransferContextStore store = persistence.transferContexts();
        UUID opId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        store.put(new TransferContext(opId, player, PortalOrientation.Z_AXIS,
                0.125, 0.875, 0.0, 0.6, 1.5, T0));

        TransferContext read = store.find(opId).orElseThrow();
        assertEquals(player, read.playerUuid());
        assertEquals(PortalOrientation.Z_AXIS, read.sourceAxis());
        assertEquals(0.125, read.fx());
        assertEquals(0.875, read.fy());
        assertEquals(0.0, read.forward());
        assertEquals(0.6, read.entityWidth());
        assertEquals(1.5, read.entityHeight());
        assertEquals(T0, read.createdAt());

        assertEquals(1, store.delete(opId));
        assertTrue(store.find(opId).isEmpty());
    }

    @Test
    void duplicatePutIsRejected() {
        TransferContextStore store = persistence.transferContexts();
        UUID opId = UUID.randomUUID();
        store.put(row(opId, T0, PortalOrientation.X_AXIS));
        assertThrows(PersistenceException.class, () -> store.put(row(opId, T1, PortalOrientation.Z_AXIS)));
    }

    @Test
    void deleteOlderThanReapsOnlyOldRows() {
        TransferContextStore store = persistence.transferContexts();
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        store.put(row(stale, T0, PortalOrientation.X_AXIS));
        store.put(row(fresh, T1, PortalOrientation.Z_AXIS));
        assertEquals(1, store.deleteOlderThan(T0.plusSeconds(10)));
        assertTrue(store.find(stale).isEmpty());
        assertEquals(Optional.of(PortalOrientation.Z_AXIS),
                store.find(fresh).map(TransferContext::sourceAxis));
    }
}

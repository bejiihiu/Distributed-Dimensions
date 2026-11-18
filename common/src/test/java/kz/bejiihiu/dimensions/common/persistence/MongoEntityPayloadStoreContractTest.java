package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload-store contract against a live MongoDB. Runs only when {@code TEST_MONGODB_URI}
 * is set; each test gets a unique scratch database that is dropped afterwards.
 */
class MongoEntityPayloadStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T1.plusSeconds(5);

    private DimensionPersistence persistence;
    private String database;

    @BeforeEach
    void setUp() {
        String uri = System.getenv("TEST_MONGODB_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(),
                "TEST_MONGODB_URI not set — skipping MongoDB payload contract tests");
        database = "dd_contract_" + UUID.randomUUID().toString().replace('-', '_');
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

    private static EntityTransferPayload row(String opId, int seq, Instant at) {
        return new EntityTransferPayload(opId, seq, EntityPayloadKind.MOB,
                "nether", "minecraft:nether", null,
                "{\"kind\":\"mob\",\"seq\":" + seq + "}",
                EntityPayloadState.PENDING, at, at);
    }

    @Test
    void insertClaimDeleteRoundTrip() {
        EntityPayloadStore store = persistence.entityPayloads();
        String opId = UUID.randomUUID().toString();
        store.insertAll(List.of(row(opId, 0, T0), row(opId, 1, T0), row(opId, 2, T0)));

        assertEquals(List.of(opId), store.findPendingOpIds("nether", 10));

        List<EntityTransferPayload> claimed = store.claimOp(opId, "nether", T1);
        assertEquals(List.of(0, 1, 2), claimed.stream().map(EntityTransferPayload::seq).toList());
        assertTrue(claimed.stream().allMatch(r -> r.state() == EntityPayloadState.CLAIMED));
        assertEquals(List.of(), store.findPendingOpIds("nether", 10));

        assertEquals(3, store.failOp(opId, T2));
        assertEquals(3, store.deleteForOp(opId));
        assertEquals(0, store.countForOp(opId));
    }

    @Test
    void duplicateInsertIsRejected() {
        EntityPayloadStore store = persistence.entityPayloads();
        String opId = UUID.randomUUID().toString();
        store.insertAll(List.of(row(opId, 0, T0)));
        org.junit.jupiter.api.Assertions.assertThrows(PersistenceException.class,
                () -> store.insertAll(List.of(row(opId, 0, T0))));
    }
}

package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the MongoDB backend. They run only when {@code TEST_MONGODB_URI} is set
 * (e.g. a local mongod or a throwaway Atlas free cluster); otherwise they skip. No container
 * or fake is substituted for a real server — an in-memory pretender would prove nothing about
 * the actual driver semantics (duplicate key codes, index creation, BSON mapping).
 *
 * <p>Each test class run gets a unique database ({@code dd_contract_<uuid>}) which is dropped
 * afterwards, so a shared throwaway server stays clean.
 */
class MongoPersistenceContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T1.plusSeconds(5);

    private DimensionPersistence persistence;
    private String database;

    @BeforeEach
    void setUp() {
        String uri = System.getenv("TEST_MONGODB_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(),
                "TEST_MONGODB_URI not set — skipping MongoDB contract tests");
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

    private static TransferOperation sampleOperation() {
        return TransferOperation.created(
                UUID.randomUUID(), UUID.randomUUID(),
                "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(10.5, 64.0, -20.25, 90.0, 0.0),
                TransferCause.NETHER_PORTAL, T0);
    }

    @Test
    void journalRoundTripActiveFilterAndCas() {
        TransferOperation op = sampleOperation();
        persistence.journal().recordCreated(op);
        assertEquals(Optional.of(op), persistence.journal().findById(op.operationId()));
        assertEquals(List.of(op), persistence.journal().findActiveForPlayer(op.playerUuid()));

        TransferOperation preparing = op.withState(OperationState.PREPARING, T1, null, null);
        assertFalse(persistence.journal().compareAndSetState(op.operationId(), OperationState.PREPARING, preparing));
        assertTrue(persistence.journal().compareAndSetState(op.operationId(), OperationState.CREATED, preparing));

        TransferOperation failed = preparing
                .withState(OperationState.TARGET_CREATED, T2, null, null)
                .withState(OperationState.FAILED, T2, TransferError.TIMEOUT, "slow");
        assertTrue(persistence.journal().compareAndSetState(op.operationId(), OperationState.PREPARING, failed));
        TransferOperation stored = persistence.journal().findById(op.operationId()).orElseThrow();
        assertEquals(TransferError.TIMEOUT, stored.error());
        assertTrue(persistence.journal().findActiveForPlayer(op.playerUuid()).isEmpty());
    }

    @Test
    void duplicateOperationIdIsRejected() {
        TransferOperation op = sampleOperation();
        persistence.journal().recordCreated(op);
        assertThrows(DuplicateOperationException.class, () -> persistence.journal().recordCreated(op));
    }

    @Test
    void portalIndexUpsertCandidatesDelete() {
        PortalIndexEntry entry = new PortalIndexEntry(
                UUID.randomUUID(), DimensionId.NETHER, PortalOrientation.Z_AXIS,
                new BlockPos(100, 64, -100), new BlockPos(100, 64, -100), new BlockPos(100, 66, -99), T0, T0);
        persistence.portalIndex().upsert(entry);
        assertEquals(entry, persistence.portalIndex().findById(entry.portalId()).orElseThrow());
        assertEquals(1, persistence.portalIndex().count(DimensionId.NETHER));
        assertEquals(List.of(entry), persistence.portalIndex().candidatesIn(DimensionId.NETHER, 90, 110, -110, -90));
        assertTrue(persistence.portalIndex().candidatesIn(DimensionId.OVERWORLD, 90, 110, -110, -90).isEmpty());

        PortalIndexEntry moved = new PortalIndexEntry(
                entry.portalId(), DimensionId.NETHER, PortalOrientation.Z_AXIS,
                new BlockPos(500, 70, 500), new BlockPos(500, 70, 500), new BlockPos(500, 72, 501), T0, T1);
        persistence.portalIndex().upsert(moved);
        assertEquals(moved, persistence.portalIndex().findById(entry.portalId()).orElseThrow());
        assertEquals(1, persistence.portalIndex().count(DimensionId.NETHER));

        assertTrue(persistence.portalIndex().delete(entry.portalId()));
        assertEquals(0, persistence.portalIndex().count(DimensionId.NETHER));
    }
}

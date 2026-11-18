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
 * Execution-path tests for the SQL layer against H2 in PostgreSQL-compatibility mode.
 * H2 stands in for the dialect family, not "for the database": everything here exercises real
 * JDBC, Hikari, DDL and the CAS statements, just without standing up three servers.
 */
class SqlPersistenceTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T1.plusSeconds(5);

    private DimensionPersistence persistence;

    @AfterEach
    void tearDown() {
        if (persistence != null) {
            persistence.close();
            persistence = null;
        }
    }

    private static String h2Url() {
        return "jdbc:h2:mem:dd-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    private DimensionPersistence open(String prefix) {
        persistence = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .maximumPoolSize(4)
                        .minimumIdle(1)
                        .tablePrefix(prefix)
                        .build());
        return persistence;
    }

    private static TransferOperation sampleOperation() {
        return TransferOperation.created(
                UUID.randomUUID(), UUID.randomUUID(),
                "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(10.5, 64.0, -20.25, 90.0, 0.0),
                TransferCause.NETHER_PORTAL, T0);
    }

    private static PortalIndexEntry sampleEntry(DimensionId dimension, int x, int y, int z) {
        return new PortalIndexEntry(
                UUID.randomUUID(), dimension, PortalOrientation.X_AXIS,
                new BlockPos(x, y, z),
                new BlockPos(x, y, z), new BlockPos(x + 1, y + 2, z),
                T0, T0);
    }

    @Test
    void migrationRunsOnceAndIsRecorded() {
        String url = h2Url();
        var settings = InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url).maximumPoolSize(2).minimumIdle(1).build();
        try (var first = PersistenceFactory.open(settings)) {
            // second open on the same database must see the migration as already applied
        }
        try (var second = PersistenceFactory.open(settings)) {
            var journal = second.journal();
            // the schema works: record + read back
            TransferOperation op = sampleOperation();
            journal.recordCreated(op);
            assertEquals(Optional.of(op), journal.findById(op.operationId()));
        }
    }

    @Test
    void journalRoundTripAndActiveFilter() {
        var db = open("dd_");
        TransferOperation op = sampleOperation();
        db.journal().recordCreated(op);
        assertEquals(Optional.of(op), db.journal().findById(op.operationId()));
        assertEquals(List.of(op), db.journal().findActiveForPlayer(op.playerUuid()));

        TransferOperation committed = op
                .withState(OperationState.PREPARING, T1, null, null)
                .withState(OperationState.TARGET_CREATED, T1, null, null)
                .withState(OperationState.SOURCE_REMOVED, T1, null, null)
                .withState(OperationState.COMMITTED, T2, null, null);
        assertTrue(db.journal().update(committed));

        // committed rows are no longer "active"
        assertTrue(db.journal().findActiveForPlayer(op.playerUuid()).isEmpty());
        assertEquals(committed, db.journal().findById(op.operationId()).orElseThrow());
    }

    @Test
    void duplicateOperationIdIsRejected() {
        var db = open("dd_");
        TransferOperation op = sampleOperation();
        db.journal().recordCreated(op);
        DuplicateOperationException e = assertThrows(DuplicateOperationException.class,
                () -> db.journal().recordCreated(op));
        assertEquals(op.operationId(), e.operationId());
    }

    @Test
    void compareAndSetStateIsARealCas() {
        var db = open("dd_");
        TransferOperation op = sampleOperation();
        db.journal().recordCreated(op);

        TransferOperation preparing = op.withState(OperationState.PREPARING, T1, null, null);
        // expectedState mismatch: row is CREATED, we claim PREPARING -> no-op
        assertFalse(db.journal().compareAndSetState(op.operationId(), OperationState.PREPARING, preparing));
        assertEquals(OperationState.CREATED, db.journal().findById(op.operationId()).orElseThrow().state());

        // expectedState match: CAS succeeds exactly once
        assertTrue(db.journal().compareAndSetState(op.operationId(), OperationState.CREATED, preparing));
        assertFalse(db.journal().compareAndSetState(op.operationId(), OperationState.CREATED, preparing));
        assertEquals(preparing, db.journal().findById(op.operationId()).orElseThrow());

        // failure transition through the journal stores the error
        TransferOperation targetCreated = preparing.withState(OperationState.TARGET_CREATED, T1, null, null);
        assertTrue(db.journal().compareAndSetState(op.operationId(), OperationState.PREPARING, targetCreated));
        TransferOperation failed = targetCreated.withState(OperationState.FAILED, T2, TransferError.BACKEND_DOWN, "refused");
        assertTrue(db.journal().compareAndSetState(op.operationId(), OperationState.TARGET_CREATED, failed));
        TransferOperation stored = db.journal().findById(op.operationId()).orElseThrow();
        assertEquals(TransferError.BACKEND_DOWN, stored.error());
        assertEquals("refused", stored.errorMessage());
    }

    @Test
    void updateReportsMissingRows() {
        var db = open("dd_");
        assertFalse(db.journal().update(sampleOperation()));
    }

    @Test
    void listRecentReturnsNewestFirst() {
        var db = open("dd_");
        TransferOperation older = sampleOperation();
        older = new TransferOperation(older.operationId(), older.playerUuid(), older.sourceServer(),
                older.targetServer(), older.sourceDimension(), older.targetDimension(), older.cause(),
                older.targetPosition(), older.state(), T0, T0, null, null);
        db.journal().recordCreated(older);
        TransferOperation newer = sampleOperation();
        newer = new TransferOperation(newer.operationId(), newer.playerUuid(), newer.sourceServer(),
                newer.targetServer(), newer.sourceDimension(), newer.targetDimension(), newer.cause(),
                newer.targetPosition(), newer.state(), T0, T1, null, null);
        db.journal().recordCreated(newer);
        assertEquals(newer.operationId(), db.journal().listRecent(10).getFirst().operationId());
        assertEquals(older.operationId(), db.journal().listRecent(10).get(1).operationId());
    }

    @Test
    void portalIndexUpsertFindCandidatesDelete() {
        var db = open("dd_");
        PortalIndexEntry entry = sampleEntry(DimensionId.NETHER, 100, 64, -100);
        db.portalIndex().upsert(entry);
        assertEquals(entry, db.portalIndex().findById(entry.portalId()).orElseThrow());
        assertEquals(1, db.portalIndex().count(DimensionId.NETHER));

        // upsert replaces instead of duplicating
        PortalIndexEntry moved = new PortalIndexEntry(entry.portalId(), DimensionId.NETHER, PortalOrientation.Z_AXIS,
                new BlockPos(500, 70, 500), new BlockPos(500, 70, 500), new BlockPos(501, 72, 500), T0, T1);
        db.portalIndex().upsert(moved);
        assertEquals(moved, db.portalIndex().findById(entry.portalId()).orElseThrow());
        assertEquals(1, db.portalIndex().count(DimensionId.NETHER));

        // candidatesIn: entry spans inner x in [500,501], z in [500,500]
        assertEquals(List.of(moved), db.portalIndex().candidatesIn(DimensionId.NETHER, 400, 600, 400, 600));
        assertTrue(db.portalIndex().candidatesIn(DimensionId.NETHER, 400, 600, 700, 800).isEmpty());
        assertTrue(db.portalIndex().candidatesIn(DimensionId.NETHER, -50, 50, -50, 50).isEmpty());
        // inside candidate must differ — z box of the query overlapping corner
        assertEquals(List.of(moved), db.portalIndex().candidatesIn(DimensionId.NETHER, 100, 500, -100, 500));
        // different dimension stays invisible
        assertTrue(db.portalIndex().candidatesIn(DimensionId.OVERWORLD, 400, 600, 400, 600).isEmpty());

        assertTrue(db.portalIndex().delete(entry.portalId()));
        assertFalse(db.portalIndex().delete(entry.portalId()));
        assertEquals(0, db.portalIndex().count(DimensionId.NETHER));
    }

    @Test
    void portalIndexRejectsInvertedInnerBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PortalIndexEntry(
                UUID.randomUUID(), DimensionId.NETHER, PortalOrientation.X_AXIS,
                new BlockPos(0, 0, 0), new BlockPos(10, 0, 0), new BlockPos(5, 0, 0), T0, T0));
    }

    @Test
    void tablePrefixIsAppliedAndValidated() {
        var db = open("custom_prefix_");
        db.journal().recordCreated(sampleOperation());
        // and an invalid prefix never reaches the wire
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .tablePrefix("BAD-PREFIX")
                        .build());
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .tablePrefix("0leadingdigit")
                        .build());
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .tablePrefix("a".repeat(40))
                        .build());
    }

    @Test
    void settingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder().type(PersistenceType.SQL).build());
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder().type(PersistenceType.MONGODB).build());
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder().type(PersistenceType.SQL).jdbcUrl("jdbc:h2:mem:x")
                        .maximumPoolSize(0).build());
        assertThrows(IllegalArgumentException.class, () ->
                InMemoryPersistenceSettings.builder().type(PersistenceType.SQL).jdbcUrl("jdbc:h2:mem:x")
                        .maximumPoolSize(2).minimumIdle(3).build());
    }

    @Test
    void migrationRunnerRejectsDuplicateAndUnsortedVersions() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration-check;DB_CLOSE_DELAY=-1");
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaMigrationRunner(dataSource, "dd_", List.of(
                        new Migration(1, "a", List.of("SELECT 1")),
                        new Migration(1, "b", List.of("SELECT 1")))));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaMigrationRunner(dataSource, "dd_", List.of(
                        new Migration(2, "a", List.of("SELECT 1")),
                        new Migration(1, "b", List.of("SELECT 1")))));
    }
}

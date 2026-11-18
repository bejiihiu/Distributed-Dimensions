package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transfer-context contract against SQL, executed on H2 in PostgreSQL mode - the same
 * dialect stand-in the journal and payload tests use. Everything here exercises real JDBC
 * and the v4 DDL; only the engine differs from production.
 */
class SqlTransferContextStoreTest extends TransferContextStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private DimensionPersistence persistence;

    private static String h2Url() {
        return "jdbc:h2:mem:dd-contexts-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    @Override
    protected TransferContextStore openStore() {
        persistence = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .maximumPoolSize(4)
                        .minimumIdle(1)
                        .tablePrefix("dd_")
                        .build());
        return persistence.transferContexts();
    }

    @Override
    protected void closeStore() {
        if (persistence != null) {
            persistence.close();
            persistence = null;
        }
    }

    private static org.h2.jdbcx.JdbcDataSource dataSourceFor(String url) {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL(url);
        return dataSource;
    }

    @Test
    void v4MigrationAppliesOnTopOfV3Only() {
        String url = h2Url();
        // simulate a pre-v4 database: apply only migrations #1..#3 by hand
        SchemaMigrationRunner v3only = new SchemaMigrationRunner(dataSourceFor(url), "dd_",
                List.copyOf(SchemaMigrations.all("dd_").subList(0, 3)));
        assertEquals(3, v3only.migrate());
        assertEquals(List.of(1, 2, 3), v3only.appliedVersions());

        // factory open must reconcile: apply v4, keep v1..v3 untouched
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            UUID opId = UUID.randomUUID();
            db.transferContexts().put(new TransferContext(opId, null, PortalOrientation.X_AXIS,
                    0.5, 0.5, 0.0, 0.6, 1.8, T0));
            assertTrue(db.transferContexts().find(opId).isPresent());
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createdAtIndexExistsAfterMigration() throws SQLException {
        String url = h2Url();
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            assertTrue(true);   // schema is up by now
        }
        try (Connection connection = dataSourceFor(url).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            java.util.List<String> indexNames = new java.util.ArrayList<>();
            for (String tableName : List.of("dd_transfer_contexts", "DD_TRANSFER_CONTEXTS")) {
                try (ResultSet indexes = metadata.getIndexInfo(null, null, tableName, false, false)) {
                    while (indexes.next()) {
                        if (indexes.getString("INDEX_NAME") != null) {
                            indexNames.add(indexes.getString("INDEX_NAME").toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            assertTrue(indexNames.contains("dd_transfer_contexts_created_at"),
                    "expected the created_at index after v4, got " + indexNames);
        }
    }

    @Test
    void duplicateOperationIdIsRejectedByThePrimaryKey() throws SQLException {
        String url = h2Url();
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            String opId = UUID.randomUUID().toString();
            try (Connection connection = dataSourceFor(url).getConnection();
                 var statement = connection.createStatement()) {
                String sql = "INSERT INTO dd_transfer_contexts"
                        + " (op_id, source_axis, fx, fy, forward, entity_width, entity_height, created_at)"
                        + " VALUES ('" + opId + "', 'x_axis', 0.5, 0.5, 0.0, 0.6, 1.8, 1), "
                        + "('" + opId + "', 'z_axis', 0.5, 0.5, 0.0, 0.6, 1.8, 2)";
                assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            }
        }
    }
}

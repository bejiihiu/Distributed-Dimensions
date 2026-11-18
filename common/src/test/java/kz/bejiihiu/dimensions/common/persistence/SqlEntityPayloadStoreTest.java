package kz.bejiihiu.dimensions.common.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload-store contract against SQL, executed on H2 in PostgreSQL mode — the same
 * dialect stand-in the journal's tests use. Everything here exercises real JDBC, the v3
 * DDL and the claim path; only the engine differs from production.
 */
class SqlEntityPayloadStoreTest extends EntityPayloadStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private DimensionPersistence persistence;

    private static String h2Url() {
        return "jdbc:h2:mem:dd-payloads-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    @Override
    protected EntityPayloadStore openStore() {
        persistence = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .maximumPoolSize(4)
                        .minimumIdle(1)
                        .tablePrefix("dd_")
                        .build());
        return persistence.entityPayloads();
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
    void v3MigrationAppliesOnTopOfV2Only() {
        String url = h2Url();
        // simulate a pre-v3 database: apply only migrations #1 and #2 by hand
        SchemaMigrationRunner v2only = new SchemaMigrationRunner(dataSourceFor(url), "dd_",
                List.copyOf(SchemaMigrations.all("dd_").subList(0, 2)));
        assertEquals(2, v2only.migrate());
        assertEquals(List.of(1, 2), v2only.appliedVersions());

        // factory open must reconcile: apply v3, keep v1/v2 untouched
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            String opId = UUID.randomUUID().toString();
            db.entityPayloads().insertAll(List.of(
                    new kz.bejiihiu.dimensions.common.model.EntityTransferPayload(
                            opId, 0, kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MOB,
                            "nether", "minecraft:nether", null,
                            "{}", kz.bejiihiu.dimensions.common.model.EntityPayloadState.PENDING, T0, T0)));
            assertEquals(1, db.entityPayloads().countForOp(opId));
        }
    }

    @Test
    void primaryKeyEnforcesOneRowPerOpAndSeq() {
        String url = h2Url();
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            String opId = UUID.randomUUID().toString();
            try (Statement statement = dataSourceFor(url).getConnection().createStatement()) {
                String sql = "INSERT INTO dd_entity_transfer_payloads"
                        + " (op_id, seq, kind, target_server, target_dimension, payload_json, state, created_at, updated_at)"
                        + " VALUES ('" + opId + "', 0, 'item', 'nether', 'minecraft:nether', '{}', 'pending', 1, 1), \n"
                        + "('" + opId + "', 0, 'item', 'nether', 'minecraft:nether', '{}', 'pending', 1, 1)";
                assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void stateAndCreatedAtIndexExistsAfterMigration() throws SQLException {
        String url = h2Url();
        try (var db = PersistenceFactory.open(InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL).jdbcUrl(url)
                .maximumPoolSize(2).minimumIdle(1).tablePrefix("dd_").build())) {
            db.entityPayloads(); // schema is up by now
        }
        try (Connection connection = dataSourceFor(url).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            java.util.List<String> indexNames = new java.util.ArrayList<>();
            for (String tableName : List.of("dd_entity_transfer_payloads", "DD_ENTITY_TRANSFER_PAYLOADS")) {
                try (ResultSet indexes = metadata.getIndexInfo(null, null, tableName, false, false)) {
                    while (indexes.next()) {
                        if (indexes.getString("INDEX_NAME") != null) {
                            indexNames.add(indexes.getString("INDEX_NAME").toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
                if (!indexNames.isEmpty()) {
                    break;
                }
            }
            assertTrue(indexNames.stream().anyMatch(n -> n.contains("state")),
                    "expected a state index on dd_entity_transfer_payloads, got " + indexNames);
        }
    }
}

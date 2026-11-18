package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
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
 * The respawn-point contract against SQL, executed on H2 in PostgreSQL mode — the same
 * dialect stand-in the journal's execution-path tests use. Everything here exercises real
 * JDBC, the v2 DDL and the upsert path; only the engine differs from production.
 */
class SqlRespawnPointStoreTest extends RespawnPointStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private DimensionPersistence persistence;

    private static String h2Url() {
        return "jdbc:h2:mem:dd-respawn-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    @Override
    protected RespawnPointStore openStore() {
        persistence = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(h2Url())
                        .maximumPoolSize(4)
                        .minimumIdle(1)
                        .tablePrefix("dd_")
                        .build());
        return persistence.respawnPoints();
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
    void v2MigrationAppliesOnTopOfV1Only() {
        String url = h2Url();
        var settings = InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL)
                .jdbcUrl(url)
                .maximumPoolSize(2)
                .minimumIdle(1)
                .tablePrefix("dd_")
                .build();
        // simulate a pre-v2 database: apply only migration #1 by hand
        SchemaMigrationRunner v1only = new SchemaMigrationRunner(dataSourceFor(url), "dd_",
                List.copyOf(SchemaMigrations.all("dd_").subList(0, 1)));
        assertEquals(1, v1only.migrate());
        assertEquals(List.of(1), v1only.appliedVersions());

        // factory open must reconcile: apply v2, keep v1 untouched
        try (var db = PersistenceFactory.open(settings)) {
            RespawnPointRecord point = new RespawnPointRecord(
                    UUID.randomUUID(), DimensionId.NETHER, "world_nether",
                    new Position(1.5, 64.0, -2.5, 90.0, 0.0), RespawnKind.RESPAWN_ANCHOR, T0);
            db.respawnPoints().upsert(point);
            assertEquals(point, db.respawnPoints().find(point.playerUuid()).orElseThrow());
            // journal still works after the reconciliation pass
            assertEquals(List.of(), db.journal().findActiveForPlayer(UUID.randomUUID()));
        }
    }

    @Test
    void schemaEnforcesOneRowPerPlayer() {
        String url = h2Url();
        var settings = InMemoryPersistenceSettings.builder()
                .type(PersistenceType.SQL)
                .jdbcUrl(url)
                .maximumPoolSize(2)
                .minimumIdle(1)
                .tablePrefix("dd_")
                .build();
        try (var db = PersistenceFactory.open(settings);
             Connection connection = dataSourceFor(url).getConnection()) {
            RespawnPointRecord point = new RespawnPointRecord(
                    UUID.randomUUID(), DimensionId.OVERWORLD, "world",
                    new Position(1.5, 64.0, -2.5, 0.0, 0.0), RespawnKind.BED, T0);
            db.respawnPoints().upsert(point);
            // a direct second row for the same player must be impossible — the primary key is player_uuid
            try (Statement statement = connection.createStatement()) {
                String sql = "INSERT INTO dd_respawn_points"
                        + " (player_uuid, dimension, world, x, y, z, yaw, pitch, kind, updated_at)"
                        + " VALUES ('" + point.playerUuid() + "', 'minecraft:overworld', 'world',"
                        + " 1, 2, 3, 0, 0, 'bed', 123)";
                assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void updatedAtIndexExistsAfterMigration() throws SQLException {
        String url = h2Url();
        try (var db = PersistenceFactory.open(
                InMemoryPersistenceSettings.builder()
                        .type(PersistenceType.SQL)
                        .jdbcUrl(url)
                        .maximumPoolSize(2)
                        .minimumIdle(1)
                        .tablePrefix("dd_")
                        .build())) {
            db.respawnPoints(); // schema is up by now; close order keeps the H2 database alive
        }
        try (Connection connection = dataSourceFor(url).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            java.util.List<String> indexNames = new java.util.ArrayList<>();
            // H2 upper-cases unquoted identifiers; probe both casings to stay honest
            for (String tableName : List.of("dd_respawn_points", "DD_RESPAWN_POINTS")) {
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
            assertTrue(indexNames.stream().anyMatch(n -> n.contains("updated_at")),
                    "expected an updated_at index on dd_respawn_points, got " + indexNames);
        }
    }
}

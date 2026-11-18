package kz.bejiihiu.dimensions.common.persistence;

import java.util.List;

/**
 * The DDL for the plugin's own state, expressed as versioned migrations.
 *
 * <p>Standard ANSI-ish SQL chosen deliberately: identical source runs on MySQL 8+, MariaDB
 * 10.x+ and PostgreSQL 14+. Two rules keep that true:
 * <ul>
 *   <li>Timestamps are {@code BIGINT} epoch milliseconds — no {@code TIMESTAMP}/{@code DATETIME}
 *       dialect split and no timezone surprises.</li>
 *   <li>Identifiers and enums travel as {@code VARCHAR} in lower-snake form (matching the wire
 *       protocol), never as native enum types.</li>
 * </ul>
 *
 * <p>Adding a migration: append a {@code new Migration(N, …)} — never edit an applied one.
 */
public final class SchemaMigrations {

    private SchemaMigrations() {
    }

    /** All known migrations for the given (normalized) table prefix, in version order. */
    public static List<Migration> all(String normalizedPrefix) {
        String ops = TableNames.table(normalizedPrefix, "transfer_operations");
        String portals = TableNames.table(normalizedPrefix, "portal_index");
        String playerStateIndex = TableNames.index(normalizedPrefix, "transfer_operations_player_state");
        String updatedIndex = TableNames.index(normalizedPrefix, "transfer_operations_updated_at");
        String dimensionIndex = TableNames.index(normalizedPrefix, "portal_index_dimension_origin");
        String respawnPoints = TableNames.table(normalizedPrefix, "respawn_points");
        String respawnUpdatedIndex = TableNames.index(normalizedPrefix, "respawn_points_updated_at");

        return List.of(
                new Migration(1, "initial schema (transfer journal, portal index)",
                        List.of(
                                """
                                CREATE TABLE IF NOT EXISTS %s (
                                    operation_id     VARCHAR(36)  PRIMARY KEY,
                                    player_uuid      VARCHAR(36)  NOT NULL,
                                    source_server    VARCHAR(64)  NOT NULL,
                                    target_server    VARCHAR(64)  NOT NULL,
                                    source_dimension VARCHAR(128) NOT NULL,
                                    target_dimension VARCHAR(128) NOT NULL,
                                    cause            VARCHAR(32)  NOT NULL,
                                    state            VARCHAR(16)  NOT NULL,
                                    target_x         DOUBLE       NOT NULL,
                                    target_y         DOUBLE       NOT NULL,
                                    target_z         DOUBLE       NOT NULL,
                                    target_yaw       DOUBLE       NOT NULL,
                                    target_pitch     DOUBLE       NOT NULL,
                                    created_at       BIGINT       NOT NULL,
                                    updated_at       BIGINT       NOT NULL,
                                    error_code       VARCHAR(32),
                                    error_message    VARCHAR(512)
                                )""".formatted(ops),
                                "CREATE INDEX " + playerStateIndex + " ON " + ops + " (player_uuid, state)",
                                "CREATE INDEX " + updatedIndex + " ON " + ops + " (updated_at)",
                                """
                                CREATE TABLE IF NOT EXISTS %s (
                                    portal_id    VARCHAR(36) PRIMARY KEY,
                                    dimension    VARCHAR(128) NOT NULL,
                                    orientation  VARCHAR(16)  NOT NULL,
                                    origin_x     INT          NOT NULL,
                                    origin_y     INT          NOT NULL,
                                    origin_z     INT          NOT NULL,
                                    inner_min_x  INT          NOT NULL,
                                    inner_min_y  INT          NOT NULL,
                                    inner_min_z  INT          NOT NULL,
                                    inner_max_x  INT          NOT NULL,
                                    inner_max_y  INT          NOT NULL,
                                    inner_max_z  INT          NOT NULL,
                                    created_at   BIGINT       NOT NULL,
                                    updated_at   BIGINT       NOT NULL
                                )""".formatted(portals),
                                "CREATE INDEX " + dimensionIndex + " ON " + portals + " (dimension, origin_x, origin_z)"
                        )),
                new Migration(2, "respawn points table",
                        List.of(
                                """
                                CREATE TABLE IF NOT EXISTS %s (
                                    player_uuid   VARCHAR(36)     PRIMARY KEY,
                                    dimension     VARCHAR(64)     NOT NULL,
                                    world         VARCHAR(128)    NOT NULL,
                                    x             DOUBLE PRECISION NOT NULL,
                                    y             DOUBLE PRECISION NOT NULL,
                                    z             DOUBLE PRECISION NOT NULL,
                                    yaw           DOUBLE PRECISION NOT NULL,
                                    pitch         DOUBLE PRECISION NOT NULL,
                                    kind          VARCHAR(16)     NOT NULL,
                                    updated_at    BIGINT          NOT NULL
                                )""".formatted(respawnPoints),
                                "CREATE INDEX " + respawnUpdatedIndex + " ON " + respawnPoints + " (updated_at)"
                        )));
    }
}

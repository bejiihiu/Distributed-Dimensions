package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL/JDBC implementation of the respawn-point store — one standard dialect across
 * MySQL, MariaDB and PostgreSQL. All statements are parameterized; nothing is ever
 * string-concatenated from input.
 *
 * <p>The upsert is {@code UPDATE} first, then {@code INSERT} (retried as an
 * {@code UPDATE} on a unique violation). That trade avoids the dialect-specific
 * {@code ON CONFLICT} / {@code ON DUPLICATE KEY} forks entirely: MySQL has no
 * {@code ON CONFLICT}, PostgreSQL has no {@code ON DUPLICATE KEY}, and H2's
 * PostgreSQL mode matches PostgreSQL. Concurrent upserts for the same player serialize
 * on the primary key and the loser rewrites the winner's row — last write wins, which
 * is the intended semantic.
 */
final class SqlRespawnPointStore implements RespawnPointStore {

    private final DataSource dataSource;
    private final String table;

    SqlRespawnPointStore(DataSource dataSource, String normalizedTablePrefix) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.table = TableNames.table(normalizedTablePrefix, "respawn_points");
    }

    @Override
    public void upsert(RespawnPointRecord point) {
        try (Connection connection = dataSource.getConnection()) {
            if (update(connection, point)) {
                return;
            }
            try {
                insert(connection, point);
            } catch (SQLException e) {
                // lost the insert race against a parallel upsert — rewrite our row onto theirs
                if (!SchemaMigrationRunner.isUniqueViolation(e)) {
                    throw e;
                }
                update(connection, point);
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to upsert respawn point for player " + point.playerUuid(), e);
        }
    }

    @Override
    public Optional<RespawnPointRecord> find(UUID playerUuid) {
        String sql = "SELECT * FROM " + table + " WHERE player_uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("find failed for player " + playerUuid, e);
        }
    }

    @Override
    public boolean delete(UUID playerUuid) {
        String sql = "DELETE FROM " + table + " WHERE player_uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PersistenceException("delete failed for player " + playerUuid, e);
        }
    }

    private boolean update(Connection connection, RespawnPointRecord point) throws SQLException {
        String sql = "UPDATE " + table
                + " SET dimension = ?, world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, kind = ?, updated_at = ?"
                + " WHERE player_uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, point.dimension().key());
            ps.setString(2, point.world());
            ps.setDouble(3, point.position().x());
            ps.setDouble(4, point.position().y());
            ps.setDouble(5, point.position().z());
            ps.setDouble(6, point.position().yaw());
            ps.setDouble(7, point.position().pitch());
            ps.setString(8, point.kind().wireName());
            ps.setLong(9, point.updatedAt().toEpochMilli());
            ps.setString(10, point.playerUuid().toString());
            return ps.executeUpdate() == 1;
        }
    }

    private void insert(Connection connection, RespawnPointRecord point) throws SQLException {
        String sql = "INSERT INTO " + table
                + " (player_uuid, dimension, world, x, y, z, yaw, pitch, kind, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, point.playerUuid().toString());
            ps.setString(2, point.dimension().key());
            ps.setString(3, point.world());
            ps.setDouble(4, point.position().x());
            ps.setDouble(5, point.position().y());
            ps.setDouble(6, point.position().z());
            ps.setDouble(7, point.position().yaw());
            ps.setDouble(8, point.position().pitch());
            ps.setString(9, point.kind().wireName());
            ps.setLong(10, point.updatedAt().toEpochMilli());
            ps.executeUpdate();
        }
    }

    static RespawnPointRecord mapRow(ResultSet rs) throws SQLException {
        Position position = new Position(
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getDouble("yaw"), rs.getDouble("pitch"));
        return new RespawnPointRecord(
                UUID.fromString(rs.getString("player_uuid")),
                DimensionId.of(rs.getString("dimension")),
                rs.getString("world"),
                position,
                RespawnKind.fromWireName(rs.getString("kind")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }
}

package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL/JDBC implementation of the context store — the same standard dialect across
 * MySQL, MariaDB and PostgreSQL as the journal and the payload store. All statements are
 * parameterized; identifiers come from {@link TableNames}.
 */
final class SqlTransferContextStore implements TransferContextStore {

    private final DataSource dataSource;
    private final String table;

    SqlTransferContextStore(DataSource dataSource, String normalizedTablePrefix) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.table = TableNames.table(normalizedTablePrefix, "transfer_contexts");
    }

    @Override
    public void put(TransferContext context) {
        String sql = "INSERT INTO " + table
                + " (op_id, player_uuid, source_axis, fx, fy, forward, entity_width, entity_height, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, context.operationId().toString());
            ps.setString(2, context.playerUuid() == null ? null : context.playerUuid().toString());
            ps.setString(3, context.sourceAxis().wireName());
            ps.setDouble(4, context.fx());
            ps.setDouble(5, context.fy());
            ps.setDouble(6, context.forward());
            ps.setDouble(7, context.entityWidth());
            ps.setDouble(8, context.entityHeight());
            ps.setLong(9, context.createdAt().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException(
                    "failed to insert transfer context for op " + context.operationId(), e);
        }
    }

    @Override
    public Optional<TransferContext> find(UUID operationId) {
        String sql = "SELECT * FROM " + table + " WHERE op_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new PersistenceException("find failed for op " + operationId, e);
        }
    }

    @Override
    public int delete(UUID operationId) {
        String sql = "DELETE FROM " + table + " WHERE op_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId.toString());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("delete failed for op " + operationId, e);
        }
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        String sql = "DELETE FROM " + table + " WHERE created_at < ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoff.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("deleteOlderThan failed", e);
        }
    }

    static TransferContext mapRow(ResultSet rs) throws SQLException {
        String playerUuid = rs.getString("player_uuid");
        return new TransferContext(
                UUID.fromString(rs.getString("op_id")),
                playerUuid == null ? null : UUID.fromString(playerUuid),
                PortalOrientation.fromWireName(rs.getString("source_axis")),
                rs.getDouble("fx"),
                rs.getDouble("fy"),
                rs.getDouble("forward"),
                rs.getDouble("entity_width"),
                rs.getDouble("entity_height"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }
}

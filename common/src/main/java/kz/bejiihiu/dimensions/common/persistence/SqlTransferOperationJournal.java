package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL/JDBC implementation of the journal — one standard dialect across MySQL, MariaDB and
 * PostgreSQL. All statements are parameterized; nothing is ever string-concatenated from input.
 */
final class SqlTransferOperationJournal implements TransferOperationJournal {

    private final DataSource dataSource;
    private final String ops;

    SqlTransferOperationJournal(DataSource dataSource, String normalizedTablePrefix) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.ops = TableNames.table(normalizedTablePrefix, "transfer_operations");
    }

    @Override
    public void recordCreated(TransferOperation operation) throws DuplicateOperationException {
        String sql = "INSERT INTO " + ops + " (operation_id, player_uuid, source_server, target_server,"
                + " source_dimension, target_dimension, cause, state, target_x, target_y, target_z, target_yaw, target_pitch,"
                + " created_at, updated_at, error_code, error_message)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operation.operationId().toString());
            ps.setString(2, operation.playerUuid().toString());
            ps.setString(3, operation.sourceServer());
            ps.setString(4, operation.targetServer());
            ps.setString(5, operation.sourceDimension().key());
            ps.setString(6, operation.targetDimension().key());
            ps.setString(7, operation.cause().wireName());
            ps.setString(8, operation.state().wireName());
            ps.setDouble(9, operation.targetPosition().x());
            ps.setDouble(10, operation.targetPosition().y());
            ps.setDouble(11, operation.targetPosition().z());
            ps.setDouble(12, operation.targetPosition().yaw());
            ps.setDouble(13, operation.targetPosition().pitch());
            ps.setLong(14, operation.createdAt().toEpochMilli());
            ps.setLong(15, operation.updatedAt().toEpochMilli());
            if (operation.error() != null) {
                ps.setString(16, operation.error().wireName());
            } else {
                ps.setNull(16, java.sql.Types.VARCHAR);
            }
            if (operation.errorMessage() != null) {
                ps.setString(17, operation.errorMessage());
            } else {
                ps.setNull(17, java.sql.Types.VARCHAR);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            if (SchemaMigrationRunner.isUniqueViolation(e)) {
                throw new DuplicateOperationException(operation.operationId(),
                        "operation " + operation.operationId() + " is already recorded", e);
            }
            throw new PersistenceException("failed to record operation " + operation.operationId(), e);
        }
    }

    @Override
    public boolean update(TransferOperation operation) {
        String sql = "UPDATE " + ops + " SET state = ?, target_x = ?, target_y = ?, target_z = ?,"
                + " target_yaw = ?, target_pitch = ?, updated_at = ?, error_code = ?, error_message = ?"
                + " WHERE operation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bindMutableColumns(ps, operation);
            ps.setString(10, operation.operationId().toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PersistenceException("failed to update operation " + operation.operationId(), e);
        }
    }

    @Override
    public boolean compareAndSetState(UUID operationId, OperationState expectedState, TransferOperation updated) {
        String sql = "UPDATE " + ops + " SET state = ?, target_x = ?, target_y = ?, target_z = ?,"
                + " target_yaw = ?, target_pitch = ?, updated_at = ?, error_code = ?, error_message = ?"
                + " WHERE operation_id = ? AND state = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bindMutableColumns(ps, updated);
            ps.setString(10, operationId.toString());
            ps.setString(11, expectedState.wireName());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PersistenceException("compareAndSetState failed for operation " + operationId, e);
        }
    }

    @Override
    public Optional<TransferOperation> findById(UUID operationId) {
        String sql = "SELECT * FROM " + ops + " WHERE operation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("findById failed for operation " + operationId, e);
        }
    }

    @Override
    public List<TransferOperation> findActiveForPlayer(UUID playerUuid) {
        String sql = "SELECT * FROM " + ops
                + " WHERE player_uuid = ? AND state NOT IN ('committed', 'failed')"
                + " ORDER BY created_at ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            return readAll(ps);
        } catch (SQLException e) {
            throw new PersistenceException("findActiveForPlayer failed for player " + playerUuid, e);
        }
    }

    @Override
    public List<TransferOperation> listRecent(int limit) {
        int clamped = Math.max(1, Math.min(1000, limit));
        String sql = "SELECT * FROM " + ops + " ORDER BY updated_at DESC, operation_id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, clamped);
            return readAll(ps);
        } catch (SQLException e) {
            throw new PersistenceException("listRecent failed", e);
        }
    }

    /** Binds columns 1-9 of the mutable-column layout shared by update and compareAndSetState. */
    private static void bindMutableColumns(PreparedStatement ps, TransferOperation operation) throws SQLException {
        ps.setString(1, operation.state().wireName());
        ps.setDouble(2, operation.targetPosition().x());
        ps.setDouble(3, operation.targetPosition().y());
        ps.setDouble(4, operation.targetPosition().z());
        ps.setDouble(5, operation.targetPosition().yaw());
        ps.setDouble(6, operation.targetPosition().pitch());
        ps.setLong(7, operation.updatedAt().toEpochMilli());
        if (operation.error() != null) {
            ps.setString(8, operation.error().wireName());
        } else {
            ps.setNull(8, java.sql.Types.VARCHAR);
        }
        if (operation.errorMessage() != null) {
            ps.setString(9, operation.errorMessage());
        } else {
            ps.setNull(9, java.sql.Types.VARCHAR);
        }
    }

    private List<TransferOperation> readAll(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<TransferOperation> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
            return rows;
        }
    }

    static TransferOperation mapRow(ResultSet rs) throws SQLException {
        Position position = new Position(
                rs.getDouble("target_x"), rs.getDouble("target_y"), rs.getDouble("target_z"),
                rs.getDouble("target_yaw"), rs.getDouble("target_pitch"));
        String errorName = rs.getString("error_code");
        return new TransferOperation(
                UUID.fromString(rs.getString("operation_id")),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("source_server"),
                rs.getString("target_server"),
                DimensionId.of(rs.getString("source_dimension")),
                DimensionId.of(rs.getString("target_dimension")),
                TransferCause.fromWireName(rs.getString("cause")),
                position,
                OperationState.fromWireName(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                errorName == null ? null : TransferError.fromWireName(errorName),
                rs.getString("error_message"));
    }
}

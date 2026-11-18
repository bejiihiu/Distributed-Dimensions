package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQL/JDBC implementation of the payload store — one standard dialect across
 * MySQL, MariaDB and PostgreSQL, mirroring the journal's conventions. All
 * statements are parameterized; identifiers come from {@link TableNames}.
 *
 * <p>{@link #claimOp} executes its compare-and-set and the bookkeeping that makes it
 * all-or-nothing inside one transaction: flip pending rows, count the op, and either
 * read the claimed rows back (happy path) or fail exactly the rows this call claimed.
 * A source-side abort racing the claim resolves to an op-wide {@code FAILED}, never
 * to a half-spawned assembly.
 */
final class SqlEntityPayloadStore implements EntityPayloadStore {

    private final DataSource dataSource;
    private final String table;

    SqlEntityPayloadStore(DataSource dataSource, String normalizedTablePrefix) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.table = TableNames.table(normalizedTablePrefix, "entity_transfer_payloads");
    }

    @Override
    public void insertAll(List<EntityTransferPayload> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + table
                + " (op_id, seq, kind, target_server, target_dimension, player_uuid,"
                + " payload_json, state, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (EntityTransferPayload row : rows) {
                ps.setString(1, row.opId());
                ps.setInt(2, row.seq());
                ps.setString(3, row.kind().wireName());
                ps.setString(4, row.targetServer());
                ps.setString(5, row.targetDimension());
                ps.setString(6, row.playerUuid() == null ? null : row.playerUuid().toString());
                ps.setString(7, row.payloadJson());
                ps.setString(8, row.state().wireName());
                ps.setLong(9, row.createdAt().toEpochMilli());
                ps.setLong(10, row.updatedAt().toEpochMilli());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new PersistenceException(
                    "failed to insert " + rows.size() + " payload row(s) for op " + rows.get(0).opId(), e);
        }
    }

    @Override
    public List<EntityTransferPayload> findByOpId(String opId) {
        String sql = "SELECT * FROM " + table + " WHERE op_id = ? ORDER BY seq";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, opId);
            try (ResultSet rs = ps.executeQuery()) {
                List<EntityTransferPayload> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new PersistenceException("findByOpId failed for op " + opId, e);
        }
    }

    @Override
    public int countForOp(String opId) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE op_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, opId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new PersistenceException("countForOp failed for op " + opId, e);
        }
    }

    @Override
    public List<String> findPendingOpIds(String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sql = "SELECT op_id FROM " + table
                + " WHERE state = ? AND target_server = ? GROUP BY op_id ORDER BY MIN(created_at)";
        return queryOpIds(sql, targetServer, limit);
    }

    @Override
    public List<String> findPendingOpIdsForPlayer(UUID playerUuid, String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // oldest first so the fast path claims the earliest pending operation for the player
        String sql = "SELECT op_id FROM " + table
                + " WHERE state = ? AND target_server = ? AND player_uuid = ?"
                + " ORDER BY created_at, op_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EntityPayloadState.PENDING.wireName());
            ps.setString(2, targetServer);
            ps.setString(3, playerUuid.toString());
            return queryLimited(ps, limit);
        } catch (SQLException e) {
            throw new PersistenceException("findPendingOpIdsForPlayer failed for target " + targetServer, e);
        }
    }

    @Override
    public List<EntityTransferPayload> claimOp(String opId, String targetServer, Instant now) {
        long nowMillis = now.toEpochMilli();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int flipped;
            int total;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + table
                            + " SET state = ?, updated_at = ?"
                            + " WHERE op_id = ? AND target_server = ? AND state = ?")) {
                ps.setString(1, EntityPayloadState.CLAIMED.wireName());
                ps.setLong(2, nowMillis);
                ps.setString(3, opId);
                ps.setString(4, targetServer);
                ps.setString(5, EntityPayloadState.PENDING.wireName());
                flipped = ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE op_id = ?")) {
                ps.setString(1, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            if (flipped == 0) {
                // nothing is pending for us — someone else owns it, it failed earlier, or
                // the op simply does not exist; rows are not ours to touch
                connection.commit();
                connection.setAutoCommit(true);
                return List.of();
            }
            if (flipped != total) {
                // inconsistent op: fail exactly the rows this call claimed, keep rows owned
                // by anyone else untouched
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE " + table
                                + " SET state = ?, updated_at = ?"
                                + " WHERE op_id = ? AND target_server = ? AND state = ? AND updated_at = ?")) {
                    ps.setString(1, EntityPayloadState.FAILED.wireName());
                    ps.setLong(2, nowMillis);
                    ps.setString(3, opId);
                    ps.setString(4, targetServer);
                    ps.setString(5, EntityPayloadState.CLAIMED.wireName());
                    ps.setLong(6, nowMillis);
                    ps.executeUpdate();
                }
                connection.commit();
                connection.setAutoCommit(true);
                return List.of();
            }
            List<EntityTransferPayload> claimed = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM " + table + " WHERE op_id = ? ORDER BY seq")) {
                ps.setString(1, opId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(mapRow(rs));
                    }
                }
            }
            connection.commit();
            connection.setAutoCommit(true);
            return List.copyOf(claimed);
        } catch (SQLException e) {
            throw new PersistenceException("claimOp failed for op " + opId, e);
        }
    }

    @Override
    public int deleteForOp(String opId) {
        String sql = "DELETE FROM " + table + " WHERE op_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, opId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("deleteForOp failed for op " + opId, e);
        }
    }

    @Override
    public int failOp(String opId, Instant now) {
        String sql = "UPDATE " + table
                + " SET state = ?, updated_at = ?"
                + " WHERE op_id = ? AND state IN (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EntityPayloadState.FAILED.wireName());
            ps.setLong(2, now.toEpochMilli());
            ps.setString(3, opId);
            ps.setString(4, EntityPayloadState.PENDING.wireName());
            ps.setString(5, EntityPayloadState.CLAIMED.wireName());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("failOp failed for op " + opId, e);
        }
    }

    @Override
    public int expireStaleClaimed(Instant claimedBefore, Instant now) {
        String sql = "UPDATE " + table
                + " SET state = ?, updated_at = ?"
                + " WHERE state = ? AND updated_at < ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EntityPayloadState.FAILED.wireName());
            ps.setLong(2, now.toEpochMilli());
            ps.setString(3, EntityPayloadState.CLAIMED.wireName());
            ps.setLong(4, claimedBefore.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("expireStaleClaimed failed", e);
        }
    }

    @Override
    public int deleteFailedOlderThan(Instant cutoff) {
        String sql = "DELETE FROM " + table + " WHERE state = ? AND created_at < ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EntityPayloadState.FAILED.wireName());
            ps.setLong(2, cutoff.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("deleteFailedOlderThan failed", e);
        }
    }

    private List<String> queryOpIds(String sql, String targetServer, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, EntityPayloadState.PENDING.wireName());
            ps.setString(2, targetServer);
            // H2 supports LIMIT inside prepared statements via setMaxRows; keep dialect-free
            ps.setMaxRows(limit);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && ids.size() < limit) {
                    ids.add(rs.getString(1));
                }
            }
            return List.copyOf(ids);
        } catch (SQLException e) {
            throw new PersistenceException("findPendingOpIds failed for target " + targetServer, e);
        }
    }

    private List<String> queryLimited(PreparedStatement ps, int limit) throws SQLException {
        ps.setMaxRows(limit);
        List<String> ids = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next() && ids.size() < limit) {
                String opId = rs.getString(1);
                if (!ids.contains(opId)) {
                    ids.add(opId);
                }
            }
        }
        return List.copyOf(ids);
    }

    static EntityTransferPayload mapRow(ResultSet rs) throws SQLException {
        String playerUuid = rs.getString("player_uuid");
        return new EntityTransferPayload(
                rs.getString("op_id"),
                rs.getInt("seq"),
                EntityPayloadKind.fromWireName(rs.getString("kind")),
                rs.getString("target_server"),
                rs.getString("target_dimension"),
                playerUuid == null ? null : UUID.fromString(playerUuid),
                rs.getString("payload_json"),
                EntityPayloadState.fromWireName(rs.getString("state")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }
}

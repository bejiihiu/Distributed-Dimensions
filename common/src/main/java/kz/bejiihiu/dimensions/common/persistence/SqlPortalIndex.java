package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL/JDBC implementation of the portal index. Overlap semantics mirror the vanilla search:
 * candidates are portals whose inner bounds intersect the query box; nothing more is claimed.
 */
final class SqlPortalIndex implements PortalIndex {

    private final DataSource dataSource;
    private final String portals;

    SqlPortalIndex(DataSource dataSource, String normalizedTablePrefix) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.portals = TableNames.table(normalizedTablePrefix, "portal_index");
    }

    @Override
    public void upsert(PortalIndexEntry entry) {
        String update = "UPDATE " + portals + " SET dimension = ?, orientation = ?,"
                + " origin_x = ?, origin_y = ?, origin_z = ?,"
                + " inner_min_x = ?, inner_min_y = ?, inner_min_z = ?,"
                + " inner_max_x = ?, inner_max_y = ?, inner_max_z = ?, updated_at = ?"
                + " WHERE portal_id = ?";
        String insert = "INSERT INTO " + portals + " (portal_id, dimension, orientation,"
                + " origin_x, origin_y, origin_z,"
                + " inner_min_x, inner_min_y, inner_min_z,"
                + " inner_max_x, inner_max_y, inner_max_z, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(update)) {
                bindColumns(ps, entry);
                ps.setString(13, entry.portalId().toString());
                if (ps.executeUpdate() == 1) {
                    return;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(insert)) {
                ps.setString(1, entry.portalId().toString());
                ps.setString(2, entry.dimension().key());
                ps.setString(3, entry.orientation().wireName());
                ps.setInt(4, entry.origin().x());
                ps.setInt(5, entry.origin().y());
                ps.setInt(6, entry.origin().z());
                ps.setInt(7, entry.innerMin().x());
                ps.setInt(8, entry.innerMin().y());
                ps.setInt(9, entry.innerMin().z());
                ps.setInt(10, entry.innerMax().x());
                ps.setInt(11, entry.innerMax().y());
                ps.setInt(12, entry.innerMax().z());
                ps.setLong(13, entry.createdAt().toEpochMilli());
                ps.setLong(14, entry.updatedAt().toEpochMilli());
                try {
                    ps.executeUpdate();
                } catch (SQLException e) {
                    // lost the insert race against a concurrent upsert of the same portal — retry the update
                    if (SchemaMigrationRunner.isUniqueViolation(e)) {
                        try (PreparedStatement retry = connection.prepareStatement(update)) {
                            bindColumns(retry, entry);
                            retry.setString(13, entry.portalId().toString());
                            retry.executeUpdate();
                        }
                        return;
                    }
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to upsert portal " + entry.portalId(), e);
        }
    }

    /** Binds the 12 leading columns of the update statement (dimension..updated_at). */
    private static void bindColumns(PreparedStatement ps, PortalIndexEntry entry) throws SQLException {
        ps.setString(1, entry.dimension().key());
        ps.setString(2, entry.orientation().wireName());
        ps.setInt(3, entry.origin().x());
        ps.setInt(4, entry.origin().y());
        ps.setInt(5, entry.origin().z());
        ps.setInt(6, entry.innerMin().x());
        ps.setInt(7, entry.innerMin().y());
        ps.setInt(8, entry.innerMin().z());
        ps.setInt(9, entry.innerMax().x());
        ps.setInt(10, entry.innerMax().y());
        ps.setInt(11, entry.innerMax().z());
        ps.setLong(12, entry.updatedAt().toEpochMilli());
    }

    @Override
    public Optional<PortalIndexEntry> findById(UUID portalId) {
        String sql = "SELECT * FROM " + portals + " WHERE portal_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, portalId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("findById failed for portal " + portalId, e);
        }
    }

    @Override
    public List<PortalIndexEntry> candidatesIn(DimensionId dimension, int minX, int maxX, int minZ, int maxZ) {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("empty search box [" + minX + "," + maxX + "] x [" + minZ + "," + maxZ + "]");
        }
        String sql = "SELECT * FROM " + portals
                + " WHERE dimension = ? AND inner_max_x >= ? AND inner_min_x <= ?"
                + " AND inner_max_z >= ? AND inner_min_z <= ?"
                + " ORDER BY origin_x, origin_z";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dimension.key());
            ps.setInt(2, minX);
            ps.setInt(3, maxX);
            ps.setInt(4, minZ);
            ps.setInt(5, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                List<PortalIndexEntry> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new PersistenceException("candidatesIn failed for dimension " + dimension, e);
        }
    }

    @Override
    public boolean delete(UUID portalId) {
        String sql = "DELETE FROM " + portals + " WHERE portal_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, portalId.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PersistenceException("delete failed for portal " + portalId, e);
        }
    }

    @Override
    public long count(DimensionId dimension) {
        String sql = "SELECT COUNT(*) FROM " + portals + " WHERE dimension = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dimension.key());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new PersistenceException("count failed for dimension " + dimension, e);
        }
    }

    static PortalIndexEntry mapRow(ResultSet rs) throws SQLException {
        return new PortalIndexEntry(
                UUID.fromString(rs.getString("portal_id")),
                DimensionId.of(rs.getString("dimension")),
                PortalOrientation.fromWireName(rs.getString("orientation")),
                new BlockPos(rs.getInt("origin_x"), rs.getInt("origin_y"), rs.getInt("origin_z")),
                new BlockPos(rs.getInt("inner_min_x"), rs.getInt("inner_min_y"), rs.getInt("inner_min_z")),
                new BlockPos(rs.getInt("inner_max_x"), rs.getInt("inner_max_y"), rs.getInt("inner_max_z")),
                java.time.Instant.ofEpochMilli(rs.getLong("created_at")),
                java.time.Instant.ofEpochMilli(rs.getLong("updated_at")));
    }
}

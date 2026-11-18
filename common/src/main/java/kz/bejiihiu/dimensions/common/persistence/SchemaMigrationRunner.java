package kz.bejiihiu.dimensions.common.persistence;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies versioned migrations and records them in {@code <prefix>schema_migrations}.
 *
 * <p>Properties:
 * <ul>
 *   <li>Boot-time, one-shot: open, migrate, done. Not built for concurrent use against the
 *       same database — two racing starters both see {@code CREATE TABLE IF NOT EXISTS} for the
 *       bookkeeping table and then serialize on the primary key of the applied row.</li>
 *   <li>Each migration runs in one transaction where the dialect supports transactional DDL
 *       (PostgreSQL, H2). MySQL implicitly commits on DDL — a half-applied migration on MySQL
 *       therefore aborts startup, and the operator inspects the schema by hand. That is the
 *       honest price of keeping the tool tiny; the alternative (Liquibase) is not proportionate
 *       here.</li>
 *   <li>Migrations never run twice and are never re-ordered: the recorded version is the
 *       applying party's own attestation.</li>
 * </ul>
 */
public final class SchemaMigrationRunner {

    private final DataSource dataSource;
    private final String normalizedPrefix;
    private final List<Migration> migrations;

    public SchemaMigrationRunner(DataSource dataSource, String tablePrefix, List<Migration> migrations) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.normalizedPrefix = TableNames.normalize(tablePrefix);
        this.migrations = List.copyOf(java.util.Objects.requireNonNull(migrations, "migrations"));
        validateOrder(this.migrations);
    }

    /** Applies pending migrations; returns how many were applied. */
    public int migrate() {
        try (Connection connection = dataSource.getConnection()) {
            ensureBookkeepingTable(connection);
            int applied = 0;
            for (Migration migration : migrations) {
                if (isApplied(connection, migration.version())) {
                    continue;
                }
                try {
                    apply(connection, migration);
                } catch (MigrationException e) {
                    // lost the race against another starter applying the same migration? then the
                    // bookkeeping says it's done now and we can proceed; otherwise it's a real failure
                    if (allApplied(connection)) {
                        return applied;
                    }
                    throw e;
                }
                applied++;
            }
            return applied;
        } catch (SQLException e) {
            throw new MigrationException("schema migration failed: " + e.getMessage(), e);
        }
    }

    private boolean allApplied(Connection connection) throws SQLException {
        Set<Integer> applied = new HashSet<>(readApplied(connection));
        return migrations.stream().allMatch(m -> applied.contains(m.version()));
    }

    /** Versions already recorded as applied, ascending. */
    public List<Integer> appliedVersions() {
        try (Connection connection = dataSource.getConnection()) {
            ensureBookkeepingTable(connection);
            return readApplied(connection);
        } catch (SQLException e) {
            throw new MigrationException("cannot read applied migrations: " + e.getMessage(), e);
        }
    }

    private String bookkeepingTable() {
        return TableNames.table(normalizedPrefix, "schema_migrations");
    }

    private void ensureBookkeepingTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + bookkeepingTable() + " ("
                            + "version     INT          PRIMARY KEY,"
                            + "description  VARCHAR(255) NOT NULL,"
                            + "applied_at   BIGINT       NOT NULL"
                            + ")");
        }
    }

    private boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM " + bookkeepingTable() + " WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Index creation is made idempotent for the re-run/racing-starter case — plain
     * {@code CREATE INDEX} is not portable across our three engines (MySQL has no
     * {@code IF NOT EXISTS}), so we probe {@code DatabaseMetaData} first instead.
     * Table DDL in the migrations already uses {@code IF NOT EXISTS}.
     */
    private void executeStatements(Connection connection, List<String> statements) throws SQLException {
        for (String sql : statements) {
            String stripped = sql.strip();
            java.util.regex.Matcher index = CREATE_INDEX.matcher(stripped);
            if (index.find()) {
                createIndexIfMissing(connection, stripped, index.group(1), index.group(2));
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(stripped);
            }
        }
    }

    private void createIndexIfMissing(Connection connection, String createIndexSql,
                                      String indexName, String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(createIndexSql);
        }
    }

    private static final java.util.regex.Pattern CREATE_INDEX =
            java.util.regex.Pattern.compile("(?i)CREATE\\s+INDEX\\s+([A-Za-z0-9_]+)\\s+ON\\s+([A-Za-z0-9_]+)");

    private List<Integer> readApplied(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM " + bookkeepingTable() + " ORDER BY version")) {
            List<Integer> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
            return versions;
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        boolean transactional = supportsTransactionalDdl(connection);
        if (transactional) {
            connection.setAutoCommit(false);
        }
        try {
            executeStatements(connection, migration.statements());
            recordApplied(connection, migration);
            if (transactional) {
                connection.commit();
            }
        } catch (SQLException e) {
            if (transactional) {
                connection.rollback();
            }
            throw new MigrationException(
                    "migration #" + migration.version() + " ('" + migration.description() + "') failed: " + e.getMessage(), e);
        } finally {
            if (transactional) {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /**
     * PostgreSQL and H2 support transactional DDL; MySQL/MariaDB do not (DDL implicitly commits).
     * We ask the connection instead of trusting the URL — H2's PostgreSQL mode reports correctly
     * through the DatabaseMetaData product name.
     */
    private boolean supportsTransactionalDdl(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
        return product.contains("postgresql") || product.contains("h2");
    }

    private void recordApplied(Connection connection, Migration migration) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + bookkeepingTable() + " (version, description, applied_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, migration.version());
            ps.setString(2, migration.description());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            // a racing starter may have applied this exact migration already; treat duplicates as benign
            if (isUniqueViolation(e)) {
                return;
            }
            throw e;
        }
    }

    static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        // SQLState class 23: integrity constraint violation — 23000 generic, 23505 postgres, 23505 h2
        return state != null && state.startsWith("23");
    }

    private static void validateOrder(List<Migration> migrations) {
        Set<Integer> versions = new HashSet<>();
        int previous = 0;
        for (Migration migration : migrations) {
            if (!versions.add(migration.version())) {
                throw new IllegalArgumentException("duplicate migration version " + migration.version());
            }
            if (migration.version() <= previous) {
                throw new IllegalArgumentException("migrations must be ordered by ascending version");
            }
            previous = migration.version();
        }
    }
}

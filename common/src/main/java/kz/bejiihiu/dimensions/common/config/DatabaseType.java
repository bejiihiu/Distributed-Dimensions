package kz.bejiihiu.dimensions.common.config;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Supported database engines. SQL engines share one dialect and one driver per
 * engine; MongoDB is a document store with its own connection string.
 */
public enum DatabaseType {

    MYSQL("mysql", true),
    MARIADB("mariadb", true),
    POSTGRESQL("postgresql", true),
    MONGODB("mongodb", false);

    private final String id;
    private final boolean sql;

    DatabaseType(String id, boolean sql) {
        this.id = id;
        this.sql = sql;
    }

    /** Lower-case id used in the YAML file. */
    public String id() {
        return id;
    }

    /** True for the JDBC engines (mysql/mariadb/postgresql). */
    public boolean sql() {
        return sql;
    }

    /** Case-insensitive parse; {@code null} falls back to an error listing the known values. */
    public static DatabaseType parse(String raw) {
        if (raw != null) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (DatabaseType type : values()) {
                if (type.id.equals(normalized)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException(
                "unknown database type '" + raw + "' — expected one of: "
                        + Stream.of(values()).map(DatabaseType::id).collect(Collectors.joining(", ")));
    }
}

package kz.bejiihiu.dimensions.common.config;

import kz.bejiihiu.dimensions.common.persistence.InMemoryPersistenceSettings;
import kz.bejiihiu.dimensions.common.persistence.PersistenceSettings;
import kz.bejiihiu.dimensions.common.persistence.PersistenceType;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database connection info. All values are already resolved: {@code ${VAR}} references
 * were expanded at load time (an unset variable fails load with a clear message naming
 * the variable, never its value).
 *
 * <p>Secrets are never printed: {@link #toString()} masks the password and strips any
 * credentials embedded in the JDBC/Mongo URLs. Use {@link #toPersistenceSettings()} to
 * hand a validated, ready-to-use value to the persistence layer.
 */
public record DatabaseSettings(
        DatabaseType type,
        String jdbcUrl,
        String mongoUri,
        String username,
        String password,
        String mongoDatabase,
        int maximumPoolSize,
        int minimumIdle,
        Duration connectionTimeout,
        String tablePrefix) {

    /** Mirror of the persistence layer's prefix grammar; checked at load so health reflects it. */
    static final Pattern TABLE_PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,30}_?");

    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?<=://)[^/@\\s]+:[^/@\\s]+@");

    public DatabaseSettings {
        if (type == null) {
            throw new IllegalArgumentException("database.type must be one of: mysql, mariadb, postgresql, mongodb");
        }
        if (type.sql()) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("database.jdbc-url is required for type '" + type.id() + "'");
            }
        } else if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalArgumentException("database.mongo-uri is required for type 'mongodb'");
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("database.maximum-pool-size must be >= 1, got " + maximumPoolSize);
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "database.minimum-idle must be in [0, maximum-pool-size], got " + minimumIdle);
        }
        if (connectionTimeout == null || connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException("database.connection-timeout must be positive");
        }
        if (tablePrefix == null || !TABLE_PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException(
                    "database.table-prefix must match [a-z][a-z0-9_]{0,30} with an optional trailing '_', got '"
                            + tablePrefix + "'");
        }
    }

    /**
     * Maps this snapshot onto the persistence layer's seam. SQL engines become
     * {@link PersistenceType#SQL} (blank credentials collapse to {@code null} — the
     * seam's "no credentials" shape), MongoDB becomes {@link PersistenceType#MONGODB}.
     *
     * @throws ConfigurationException when the values do not satisfy the persistence contract
     */
    public PersistenceSettings toPersistenceSettings() {
        try {
            InMemoryPersistenceSettings.Builder builder = InMemoryPersistenceSettings.builder()
                    .type(type.sql() ? PersistenceType.SQL : PersistenceType.MONGODB)
                    .maximumPoolSize(maximumPoolSize)
                    .minimumIdle(minimumIdle)
                    .connectionTimeout(connectionTimeout)
                    .tablePrefix(tablePrefix);
            if (type.sql()) {
                builder.jdbcUrl(strip(jdbcUrl))
                        .username(strip(username))
                        .password(strip(password));
            } else {
                builder.mongoUri(strip(mongoUri))
                        .mongoDatabase(strip(mongoDatabase));
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            // keep secret values out of the message: only the invalid field's problem, verbatim
            throw new ConfigurationException("database: " + e.getMessage(), e);
        }
    }

    private static String strip(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        // password renders as ****; credentials embedded in URLs are stripped the same way
        return "DatabaseSettings[type=" + type.id()
                + ", jdbcUrl=" + redactUrl(jdbcUrl)
                + ", mongoUri=" + redactUrl(mongoUri)
                + ", username=" + username
                + ", password=" + (password == null || password.isBlank() ? "<not set>" : "****")
                + ", mongoDatabase=" + mongoDatabase
                + ", maximumPoolSize=" + maximumPoolSize
                + ", minimumIdle=" + minimumIdle
                + ", connectionTimeout=" + connectionTimeout
                + ", tablePrefix=" + tablePrefix
                + "]";
    }

    private static String redactUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = URL_CREDENTIALS.matcher(url);
        return matcher.find() ? matcher.replaceAll("****@") : url;
    }
}

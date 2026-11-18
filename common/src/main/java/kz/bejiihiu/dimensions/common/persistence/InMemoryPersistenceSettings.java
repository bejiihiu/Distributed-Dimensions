package kz.bejiihiu.dimensions.common.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * A plain in-memory settings value — the canonical test double for this seam and the
 * quickest way to stand the persistence layer up against H2.
 *
 * <p>Not a store: it holds configuration, nothing else. For the SQL path you still need a
 * real JDBC URL (tests use H2 in PostgreSQL-compatibility mode); for MongoDB point
 * {@code mongoUri} at a scratch database (see the contract tests, gated on
 * {@code TEST_MONGODB_URI}).
 */
public final class InMemoryPersistenceSettings implements PersistenceSettings {

    private final PersistenceType type;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String mongoUri;
    private final String mongoDatabase;
    private final int maximumPoolSize;
    private final int minimumIdle;
    private final Duration connectionTimeout;
    private final String tablePrefix;

    private InMemoryPersistenceSettings(Builder builder) {
        this.type = builder.type;
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.mongoUri = builder.mongoUri;
        this.mongoDatabase = builder.mongoDatabase;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.minimumIdle = builder.minimumIdle;
        this.connectionTimeout = builder.connectionTimeout;
        this.tablePrefix = builder.tablePrefix;
        validate();
    }

    private void validate() {
        switch (type) {
            case SQL -> {
                if (jdbcUrl == null || jdbcUrl.isBlank()) {
                    throw new IllegalArgumentException("SQL persistence requires a jdbcUrl");
                }
            }
            case MONGODB -> {
                if (mongoUri == null || mongoUri.isBlank()) {
                    throw new IllegalArgumentException("MongoDB persistence requires a mongoUri");
                }
            }
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be >= 1, got " + maximumPoolSize);
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "minimumIdle must be in [0, maximumPoolSize], got " + minimumIdle);
        }
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException("connectionTimeout must be positive");
        }
        TableNames.normalize(tablePrefix);
    }

    @Override
    public PersistenceType type() {
        return type;
    }

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public String password() {
        return password;
    }

    @Override
    public String mongoUri() {
        return mongoUri;
    }

    @Override
    public String mongoDatabase() {
        return mongoDatabase;
    }

    @Override
    public int maximumPoolSize() {
        return maximumPoolSize;
    }

    @Override
    public int minimumIdle() {
        return minimumIdle;
    }

    @Override
    public Duration connectionTimeout() {
        return connectionTimeout;
    }

    @Override
    public String tablePrefix() {
        return tablePrefix;
    }

    /** Convenience for the test suite: SQL settings against an H2 in-memory database. */
    public static InMemoryPersistenceSettings forH2(String jdbcUrl) {
        return builder().type(PersistenceType.SQL).jdbcUrl(jdbcUrl).maximumPoolSize(4).minimumIdle(1).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PersistenceType type = PersistenceType.SQL;
        private String jdbcUrl;
        private String username;
        private String password;
        private String mongoUri;
        private String mongoDatabase;
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private String tablePrefix = "dd_";

        private Builder() {
        }

        public Builder type(PersistenceType type) {
            this.type = Objects.requireNonNull(type, "type");
            return this;
        }

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder mongoUri(String mongoUri) {
            this.mongoUri = mongoUri;
            return this;
        }

        public Builder mongoDatabase(String mongoDatabase) {
            this.mongoDatabase = mongoDatabase;
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder tablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
            return this;
        }

        public InMemoryPersistenceSettings build() {
            return new InMemoryPersistenceSettings(this);
        }
    }
}

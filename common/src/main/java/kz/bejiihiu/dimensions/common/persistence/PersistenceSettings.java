package kz.bejiihiu.dimensions.common.persistence;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Everything the persistence layer needs to open a store, in one schema-agnostic shape.
 *
 * <p>This is the seam the configuration system plugs into later — implementations only have to
 * supply values, not behavior. Invariants:
 *
 * <ul>
 *   <li>{@link #type()} {@code SQL} requires a non-null {@link #jdbcUrl()} working with one of
 *       the three supported JDBC drivers (MySQL/MariaDB/PostgreSQL — one standard dialect).
 *       {@code null} {@link #username()}/{@link #password()} means "no credentials" (rare;
 *       local trust auth).</li>
 *   <li>{@code type()} {@code MONGODB} requires a non-null {@link #mongoUri()}.
 *       {@link #mongoDatabase()} may be {@code null}, meaning {@code "distributed_dimensions"}.</li>
 *   <li>{@link #tablePrefix()} must match {@code [a-z][a-z0-9_]*} (an optional trailing
 *       underscore is allowed) and must be at most 31 characters — every table and index name
 *       is the prefix plus a suffix, and MySQL caps identifiers at 64 characters.</li>
 *   <li>{@link #maximumPoolSize()} ≥ 1, {@code 0 ≤} {@link #minimumIdle()} ≤ maximumPoolSize.
 *       For MongoDB these map to the connection-pool settings (max/min size); for SQL they go
 *       straight to Hikari.</li>
 *   <li>Secrets travel by env-var indirection: the config layer is expected to resolve
 *       {@code ${ENV_VAR}} style references (or plain empty fields meaning "read from the
 *       environment at startup") through {@link EnvSecrets} and pass the resolved values in.
 *       This interface deliberately does not read the environment itself — settings objects
 *       must stay pure values.</li>
 * </ul>
 */
public interface PersistenceSettings {

    PersistenceType type();

    // SQL only; null otherwise
    @Nullable String jdbcUrl();

    /** Pre-resolved credential — see class javadoc for the env-var indirection pattern. */
    @Nullable String username();

    /** Pre-resolved credential — see class javadoc for the env-var indirection pattern. */
    @Nullable String password();

    // MongoDB only; null otherwise
    @Nullable String mongoUri();

    /** May be null → defaults to {@code distributed_dimensions}. */
    @Nullable String mongoDatabase();

    int maximumPoolSize();

    /** SQL: Hikari {@code minimumIdle}. MongoDB: min connection pool size. */
    int minimumIdle();

    Duration connectionTimeout();

    /** See class javadoc for the grammar and length cap. */
    String tablePrefix();
}

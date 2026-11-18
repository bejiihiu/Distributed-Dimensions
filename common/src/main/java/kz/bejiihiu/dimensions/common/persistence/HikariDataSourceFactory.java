package kz.bejiihiu.dimensions.common.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Builds a {@link HikariDataSource} from the neutral settings shape. Driver loading happens
 * through the JDBC 4 service mechanism of whichever driver is on the classpath — no
 * driverClassName plumbing.
 */
final class HikariDataSourceFactory {

    private HikariDataSourceFactory() {
    }

    static HikariDataSource create(PersistenceSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (settings.jdbcUrl() == null || settings.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException("SQL persistence requires a jdbcUrl");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        if (settings.username() != null) {
            config.setUsername(settings.username());
        }
        if (settings.password() != null) {
            config.setPassword(settings.password());
        }
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        config.setPoolName("distributed-dimensions");
        // driver-native pings; no explicit validation query on the hot path
        config.setKeepaliveTime(30_000);
        return new HikariDataSource(config);
    }
}

package kz.bejiihiu.dimensions.admin;

import java.util.List;

/**
 * Everything {@code /dd info} reports, as a plain data snapshot. Built by the
 * command's production backend from live services; rendering is a pure function
 * of this record ({@link InfoLines}), which keeps the whole output testable
 * offline.
 *
 * <p>Deliberately contains no URLs, credentials or secrets: the JDBC/Mongo URLs
 * never leave the configuration layer.
 */
public record AdminInfo(
        String pluginVersion,
        String serverVersion,
        String healthStatus,
        List<String> healthReasons,
        String configSource,
        boolean configFileCreated,
        int configVersion,
        boolean configMigrated,
        int migratedFrom,
        int migratedTo,
        String databaseEngine,
        int poolMaximum,
        int poolMinimum,
        String tablePrefix,
        List<DimensionRoute> dimensions,
        boolean mirageEnabled,
        int mirageTrackedWorlds,
        int mirageFailures,
        String syncStrategy,
        String syncPhase,
        boolean syncWaitFor,
        boolean apiEnabled,
        boolean compatSyntheticEvents,
        boolean grantAdvancements,
        long uptimeMillis) {

    public AdminInfo {
        healthReasons = List.copyOf(healthReasons == null ? List.of() : healthReasons);
        dimensions = List.copyOf(dimensions == null ? List.of() : dimensions);
    }

    /** One {@code dimension -> server} route, exactly as configured. */
    public record DimensionRoute(String dimension, String server) {

        public DimensionRoute {
            if (dimension == null || dimension.isBlank() || server == null || server.isBlank()) {
                throw new IllegalArgumentException("dimension and server must not be blank");
            }
        }
    }
}

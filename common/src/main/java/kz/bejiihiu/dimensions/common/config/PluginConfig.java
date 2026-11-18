package kz.bejiihiu.dimensions.common.config;

/**
 * The immutable, read-only snapshot of the whole configuration, handed to platform
 * modules on enable. Records all the way down — nothing here is mutable.
 */
public record PluginConfig(
        int configVersion,
        GeneralSettings general,
        NetworkSettings network,
        PortalsSettings portals,
        EntitiesSettings entities,
        ApiSettings api,
        SyncSettings sync,
        RedisSettings redis,
        DatabaseSettings database,
        DebugSettings debug,
        MirageSettings mirage) {

    public PluginConfig {
        if (configVersion < 1) {
            throw new IllegalArgumentException("configVersion must be >= 1, got " + configVersion);
        }
        if (general == null || network == null || portals == null || entities == null
                || api == null || sync == null || redis == null || database == null
                || debug == null || mirage == null) {
            throw new IllegalArgumentException("all top-level sections must be present");
        }
    }
}

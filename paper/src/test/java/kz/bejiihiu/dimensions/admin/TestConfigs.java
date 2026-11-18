package kz.bejiihiu.dimensions.admin;

import kz.bejiihiu.dimensions.common.config.ApiCompatSettings;
import kz.bejiihiu.dimensions.common.config.ApiSettings;
import kz.bejiihiu.dimensions.common.config.ChunkTicketsSettings;
import kz.bejiihiu.dimensions.common.config.CrossServerTransferSettings;
import kz.bejiihiu.dimensions.common.config.DatabaseSettings;
import kz.bejiihiu.dimensions.common.config.DatabaseType;
import kz.bejiihiu.dimensions.common.config.DebugSettings;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.EndPortalsSettings;
import kz.bejiihiu.dimensions.common.config.EntitiesSettings;
import kz.bejiihiu.dimensions.common.config.GatewayPortalsSettings;
import kz.bejiihiu.dimensions.common.config.GeneralSettings;
import kz.bejiihiu.dimensions.common.config.MirageBlockEdits;
import kz.bejiihiu.dimensions.common.config.MirageNamesSettings;
import kz.bejiihiu.dimensions.common.config.MirageSettings;
import kz.bejiihiu.dimensions.common.config.NetherPortalsSettings;
import kz.bejiihiu.dimensions.common.config.NetworkProxySettings;
import kz.bejiihiu.dimensions.common.config.NetworkSettings;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.config.PortalsSettings;
import kz.bejiihiu.dimensions.common.config.RedisSettings;
import kz.bejiihiu.dimensions.common.config.SyncSettings;
import kz.bejiihiu.dimensions.common.config.TransferSettings;

import java.time.Duration;

/** Hand-built snapshots for the admin surface tests. Records are immutable; variants derive from {@link #base()}. */
final class TestConfigs {

    private TestConfigs() {
    }

    static PluginConfig base() {
        return new PluginConfig(2,
                new GeneralSettings("en", true),
                new NetworkSettings(new NetworkProxySettings(true),
                        new TransferSettings(Duration.ofMillis(10_000), 2),
                        new DimensionsSettings(
                                new DimensionTarget("overworld", "minecraft:overworld"),
                                new DimensionTarget("nether", "minecraft:the_nether"),
                                new DimensionTarget("end", "minecraft:the_end"))),
                new PortalsSettings(
                        new NetherPortalsSettings(true, true, true, true, true),
                        new EndPortalsSettings(true),
                        new GatewayPortalsSettings(true)),
                new EntitiesSettings(new CrossServerTransferSettings(false,
                        true, false, false, false, true,
                        new ChunkTicketsSettings(true, true))),
                new ApiSettings(true, new ApiCompatSettings(false, true)),
                new SyncSettings(true, true, "auto", Duration.ofMillis(5_000), 20, "", "getPlayer"),
                new RedisSettings(false),
                new DatabaseSettings(DatabaseType.MYSQL,
                        "jdbc:mysql://localhost:3306/distributed_dimensions",
                        "mongodb://localhost:27017",
                        "distributed_dimensions", "secret-password-123",
                        "distributed_dimensions", 10, 2, Duration.ofMillis(5_000), "dd_"),
                new DebugSettings(false, false, false, false),
                new MirageSettings(true,
                        new MirageNamesSettings("world", "world_nether", "world_the_end"),
                        MirageBlockEdits.CANCEL, 6000));
    }

    static PluginConfig withLanguage(PluginConfig base, String language) {
        return new PluginConfig(base.configVersion(),
                new GeneralSettings(language, base.general().strictVanillaMode()),
                base.network(), base.portals(), base.entities(), base.api(), base.sync(),
                base.redis(), base.database(), base.debug(), base.mirage());
    }

    static PluginConfig withPoolMaximum(PluginConfig base, int maximumPoolSize) {
        DatabaseSettings db = base.database();
        DatabaseSettings changed = new DatabaseSettings(db.type(), db.jdbcUrl(), db.mongoUri(),
                db.username(), db.password(), db.mongoDatabase(),
                maximumPoolSize, db.minimumIdle(), db.connectionTimeout(), db.tablePrefix());
        return new PluginConfig(base.configVersion(),
                base.general(), base.network(), base.portals(), base.entities(), base.api(),
                base.sync(), base.redis(), changed, base.debug(), base.mirage());
    }

    static PluginConfig withMirageFixedTime(PluginConfig base, long fixedTime) {
        MirageSettings mirage = base.mirage();
        MirageSettings changed = new MirageSettings(mirage.enabled(), mirage.names(),
                mirage.blockEdits(), fixedTime);
        return new PluginConfig(base.configVersion(),
                base.general(), base.network(), base.portals(), base.entities(), base.api(),
                base.sync(), base.redis(), base.database(), base.debug(), changed);
    }

    static PluginConfig withConfigVersion(PluginConfig base, int configVersion) {
        return new PluginConfig(configVersion,
                base.general(), base.network(), base.portals(), base.entities(), base.api(),
                base.sync(), base.redis(), base.database(), base.debug(), base.mirage());
    }
}

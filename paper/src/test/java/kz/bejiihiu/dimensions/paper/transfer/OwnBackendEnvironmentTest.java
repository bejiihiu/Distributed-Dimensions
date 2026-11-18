package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.config.ApiCompatSettings;
import kz.bejiihiu.dimensions.common.config.ApiSettings;
import kz.bejiihiu.dimensions.common.config.ChunkTicketsSettings;
import kz.bejiihiu.dimensions.common.config.CrossServerTransferSettings;
import kz.bejiihiu.dimensions.common.config.DatabaseSettings;
import kz.bejiihiu.dimensions.common.config.DatabaseType;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.EndPortalsSettings;
import kz.bejiihiu.dimensions.common.config.EntitiesSettings;
import kz.bejiihiu.dimensions.common.config.GeneralSettings;
import kz.bejiihiu.dimensions.common.config.MirageBlockEdits;
import kz.bejiihiu.dimensions.common.config.MirageNamesSettings;
import kz.bejiihiu.dimensions.common.config.MirageSettings;
import kz.bejiihiu.dimensions.common.config.NetherPortalsSettings;
import kz.bejiihiu.dimensions.common.config.NetworkSettings;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.config.PortalsSettings;
import kz.bejiihiu.dimensions.common.config.SyncSettings;
import kz.bejiihiu.dimensions.common.config.TransferSettings;

import org.bukkit.World.Environment;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Production resolution of this backend's own dimension environment from configuration:
 * the {@code network.source-server} value matched against the {@code network.dimensions}
 * slots, then the slot's dimension key mapped to a Bukkit environment. Anything
 * unresolvable yields null (the caller degrades to the platform respawn world).
 */
class OwnBackendEnvironmentTest {

    private static final DimensionTarget OVERWORLD = new DimensionTarget("overworld", "minecraft:overworld");
    private static final DimensionTarget NETHER = new DimensionTarget("nether", "minecraft:the_nether");
    private static final DimensionTarget END = new DimensionTarget("end", "minecraft:the_end");

    private static PluginConfig config(String sourceServer) {
        return config(sourceServer, OVERWORLD, NETHER, END);
    }

    private static PluginConfig config(String sourceServer,
            DimensionTarget overworld, DimensionTarget nether, DimensionTarget end) {
        NetworkSettings network = new NetworkSettings(new TransferSettings(Duration.ofMillis(10_000)),
                new DimensionsSettings(overworld, nether, end),
                sourceServer);
        return new PluginConfig(4,
                new GeneralSettings("en"),
                network,
                new PortalsSettings(
                        new NetherPortalsSettings(true),
                        new EndPortalsSettings(true)),
                new EntitiesSettings(new CrossServerTransferSettings(false,
                        true, false, false, false, true,
                        new ChunkTicketsSettings(true, true))),
                new ApiSettings(true, new ApiCompatSettings(false, true)),
                new SyncSettings(true, true, "auto", Duration.ofMillis(5_000), 20, "", "getPlayer"),
                new DatabaseSettings(DatabaseType.MYSQL,
                        "jdbc:mysql://localhost:3306/distributed_dimensions",
                        "mongodb://localhost:27017",
                        "distributed_dimensions", "secret-password-123",
                        "distributed_dimensions", 10, 2, Duration.ofMillis(5_000), "dd_"),
                new MirageSettings(true,
                        new MirageNamesSettings("world", "world_nether", "world_the_end"),
                        MirageBlockEdits.CANCEL, 6000));
    }

    @Test
    void resolvesOverworldSlot() {
        assertEquals(Environment.NORMAL, OwnBackendEnvironment.resolve(config("overworld")));
    }

    @Test
    void resolvesNetherSlot() {
        assertEquals(Environment.NETHER, OwnBackendEnvironment.resolve(config("nether")));
    }

    @Test
    void resolvesEndSlot() {
        assertEquals(Environment.THE_END, OwnBackendEnvironment.resolve(config("end")));
    }

    @Test
    void blankSourceServerYieldsNull() {
        assertNull(OwnBackendEnvironment.resolve(config("")));
        assertNull(OwnBackendEnvironment.resolve(config("   ")));
    }

    @Test
    void sourceServerNotNamingAnySlotYieldsNull() {
        assertNull(OwnBackendEnvironment.resolve(config("survival")));
    }

    @Test
    void serverNameMatchIsCaseInsensitiveAndTrimmed() {
        assertEquals(Environment.NETHER, OwnBackendEnvironment.resolve(config(" NETHER ")));
    }

    @Test
    void unknownDimensionKeyYieldsNull() {
        DimensionTarget custom = new DimensionTarget("custom", "minecraft:custom_dim");

        assertNull(OwnBackendEnvironment.resolve(config("custom", custom, NETHER, END)));
    }

    @Test
    void environmentComesFromTheDimensionKeyNotTheSlotPosition() {
        // a mis-wired slot still maps by its configured key — same fail-closed philosophy
        // as DimensionResolver, which routes by configured dimension
        DimensionTarget weird = new DimensionTarget("nether", "minecraft:overworld");

        assertEquals(Environment.NORMAL, OwnBackendEnvironment.resolve(config("nether", OVERWORLD, weird, END)));
    }

    @Test
    void firstSlotWinsWhenTwoSlotsNameTheSameServer() {
        DimensionTarget twin = new DimensionTarget("nether", "minecraft:the_nether");
        DimensionTarget overworldTwin = new DimensionTarget("nether", "minecraft:overworld");

        assertEquals(Environment.NORMAL,
                OwnBackendEnvironment.resolve(config("nether", overworldTwin, twin, END)));
    }
}

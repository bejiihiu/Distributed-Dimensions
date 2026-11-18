package kz.bejiihiu.dimensions.common.config;

import kz.bejiihiu.dimensions.common.persistence.PersistenceSettings;
import kz.bejiihiu.dimensions.common.persistence.PersistenceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip of the bundled defaults: parsing it must produce exactly the values the
 * file ships. If a key stops being read (or the file drifts from the schema), this fails.
 */
class BundledDefaultsRoundTripTest {

    private static final EnvLookup ENV = EnvLookup.fixed(Map.of("DB_PASSWORD", "roundtrip-password"));

    @TempDir
    Path dir;

    @Test
    void bundledDefaultsParseIntoTheDocumentedSnapshot() {
        ConfigurationService service = new ConfigurationService(Environment.resolve(dir), ENV);
        PluginConfig c = service.load();

        assertTrue(service.isHealthy(), () -> String.valueOf(service.health().orElse(null)));

        // general
        assertEquals("en", c.general().language());
        assertTrue(c.general().strictVanillaMode());

        // network
        assertTrue(c.network().proxy().enabled());
        assertEquals("", c.network().sourceServer());
        assertEquals(Duration.ofMillis(10_000), c.network().transfer().timeout());
        assertEquals(2, c.network().transfer().maxRetries());
        assertEquals(new DimensionTarget("overworld", "minecraft:overworld"), c.network().dimensions().overworld());
        assertEquals(new DimensionTarget("nether", "minecraft:the_nether"), c.network().dimensions().nether());
        assertEquals(new DimensionTarget("end", "minecraft:the_end"), c.network().dimensions().end());

        // portals
        assertTrue(c.portals().nether().enabled());
        assertTrue(c.portals().nether().preserveVanillaDelay());
        assertTrue(c.portals().nether().preserveVanillaSearch());
        assertTrue(c.portals().nether().preserveVanillaCreation());
        assertTrue(c.portals().nether().preserveVanillaOrientation());
        assertTrue(c.portals().end().enabled());
        assertTrue(c.portals().gateway().enabled());

        // entities
        assertFalse(c.entities().crossServerTransfer().enabled());
        assertTrue(c.entities().crossServerTransfer().players());
        assertFalse(c.entities().crossServerTransfer().mobs());
        assertFalse(c.entities().crossServerTransfer().items());
        assertFalse(c.entities().crossServerTransfer().vehicles());
        assertTrue(c.entities().crossServerTransfer().portalsCreate());
        assertTrue(c.entities().crossServerTransfer().chunkTickets().enabled());
        assertTrue(c.entities().crossServerTransfer().chunkTickets().ring());

        // api
        assertTrue(c.api().enabled());
        assertFalse(c.api().compat().fireSyntheticEvents());
        assertTrue(c.api().compat().grantAdvancements());

        // sync
        assertTrue(c.sync().enabled());
        assertTrue(c.sync().waitForSync());
        assertEquals("auto", c.sync().plugin());
        assertEquals(Duration.ofMillis(5_000), c.sync().waitTimeout());
        assertEquals(20, c.sync().waitTicks());
        assertEquals("", c.sync().reflectionEventClass());
        assertEquals("getPlayer", c.sync().reflectionGetter());
        assertFalse(c.sync().reflectionConfigured());

        // redis
        assertFalse(c.redis().enabled());

        // database
        assertEquals(DatabaseType.MYSQL, c.database().type());
        assertEquals("jdbc:mysql://localhost:3306/distributed_dimensions", c.database().jdbcUrl());
        assertEquals("distributed_dimensions", c.database().username());
        assertEquals("roundtrip-password", c.database().password(), "bundled ${DB_PASSWORD} must be expanded");
        assertEquals("mongodb://localhost:27017", c.database().mongoUri());
        assertEquals("distributed_dimensions", c.database().mongoDatabase());
        assertEquals(10, c.database().maximumPoolSize());
        assertEquals(2, c.database().minimumIdle());
        assertEquals(Duration.ofMillis(5_000), c.database().connectionTimeout());
        assertEquals("dd_", c.database().tablePrefix());

        // debug
        assertFalse(c.debug().portalSearch());
        assertFalse(c.debug().coordinateConversion());
        assertFalse(c.debug().transfers());
        assertFalse(c.debug().entityTransfer());

        // mirage
        assertTrue(c.mirage().enabled());
        assertEquals("world", c.mirage().names().overworld());
        assertEquals("world_nether", c.mirage().names().nether());
        assertEquals("world_the_end", c.mirage().names().end());
        assertEquals(MirageBlockEdits.CANCEL, c.mirage().blockEdits());
        assertEquals(6000, c.mirage().fixedTime());

        // schema version
        assertEquals(3, c.configVersion());
    }

    @Test
    void bundledDefaultsProduceAWorkingPersistenceSettings() {
        ConfigurationService service = new ConfigurationService(Environment.resolve(dir), ENV);
        PluginConfig config = service.load();

        PersistenceSettings persistence = config.database().toPersistenceSettings();

        assertEquals(PersistenceType.SQL, persistence.type());
        assertEquals("jdbc:mysql://localhost:3306/distributed_dimensions", persistence.jdbcUrl());
        assertEquals("roundtrip-password", persistence.password());
        assertEquals(10, persistence.maximumPoolSize());
        assertEquals(2, persistence.minimumIdle());
        assertEquals(Duration.ofMillis(5_000), persistence.connectionTimeout());
        assertEquals("dd_", persistence.tablePrefix());
    }
}

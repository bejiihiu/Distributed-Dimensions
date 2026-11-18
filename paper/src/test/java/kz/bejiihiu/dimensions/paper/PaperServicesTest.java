package kz.bejiihiu.dimensions.paper;

import kz.bejiihiu.dimensions.common.config.ConfigurationService;
import kz.bejiihiu.dimensions.common.config.EnvLookup;
import kz.bejiihiu.dimensions.common.config.Environment;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.chunktickets.FakeChunkTicketGateway;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed semantics of the service holder. The healthy path requires a fully
 * opened {@code DimensionPersistence}, which only the persistence layer can build, so
 * this suite covers the degraded paths — the ones that must never half-open.
 */
class PaperServicesTest {

    @TempDir
    Path tempDir;

    private PluginConfig loadDefaults() {
        ConfigurationService service = new ConfigurationService(
                Environment.resolve(tempDir), EnvLookup.fixed(Map.of("DB_PASSWORD", "test")), message -> { });
        return service.load();
    }

    private PluginConfig loadBroken() throws java.io.IOException {
        // A file that fails validation (config-version must be an integer) falls back
        // to the bundled defaults with health() != empty — never a crash, never a
        // half-loaded snapshot.
        Files.writeString(tempDir.resolve("config.yml"), "config-version: \"abc\"\n");
        ConfigurationService service = new ConfigurationService(
                Environment.resolve(tempDir), EnvLookup.fixed(Map.of("DB_PASSWORD", "test")), message -> { });
        return service.load();
    }

    private PaperServices degradedHolder(PluginConfig config, boolean configHealthy,
            String persistenceFailure) {
        FakeSchedulers schedulers = new FakeSchedulers();
        ChunkTicketService tickets = new ChunkTicketService(schedulers,
                new FakeChunkTicketGateway(), schedulers::currentTick, true, false, message -> { });
        DimensionResolver dimensions = DimensionResolver.from(config.network().dimensions());
        return new PaperServices(config, configHealthy,
                configHealthy ? java.util.Optional.empty() : java.util.Optional.of("forced broken config"),
                null, persistenceFailure, schedulers, tickets, dimensions, message -> { });
    }

    @Test
    void persistenceFailureFailsClosedWithReason() {
        PluginConfig config = loadDefaults();
        PaperServices holder = degradedHolder(config, true, "boom: migration failed");

        assertFalse(holder.isHealthy());
        assertEquals(HealthReport.Status.DEGRADED, holder.health().status());
        assertTrue(holder.health().reasons().stream().anyMatch(r -> r.contains("persistence unavailable")));

        IllegalStateException e = assertThrows(IllegalStateException.class, holder::persistence);
        assertTrue(e.getMessage().contains("fail-closed"));
        assertThrows(IllegalStateException.class, holder::journal);
        assertThrows(IllegalStateException.class, holder::portalIndex);
    }

    @Test
    void configFailureAndPersistenceFailureBothSurface() throws java.io.IOException {
        PluginConfig config = loadBroken();
        PaperServices holder = degradedHolder(config, false, "boom: no database");

        assertFalse(holder.isHealthy());
        assertFalse(holder.configHealthy());
        assertTrue(holder.configFailure().isPresent());
        assertEquals(2, holder.health().reasons().size(),
                "one reason for the config fallback, one for the database: " + holder.health().reasons());
    }

    @Test
    void dimensionMiswireAlsoDegrades() {
        PluginConfig config = loadDefaults();
        FakeSchedulers schedulers = new FakeSchedulers();
        ChunkTicketService tickets = new ChunkTicketService(schedulers,
                new FakeChunkTicketGateway(), schedulers::currentTick, true, false, message -> { });
        DimensionResolver brokenDimensions = DimensionResolver.from(
                new kz.bejiihiu.dimensions.common.config.DimensionsSettings(
                        new kz.bejiihiu.dimensions.common.config.DimensionTarget(
                                "overworld", "minecraft:the_end"),
                        config.network().dimensions().nether(),
                        config.network().dimensions().end()));
        PaperServices holder = new PaperServices(config, true, java.util.Optional.empty(),
                null, "db down", schedulers, tickets, brokenDimensions, message -> { });

        assertFalse(holder.isHealthy());
        assertTrue(holder.health().reasons().stream().anyMatch(r -> r.contains("overworld")));
    }

    @Test
    void closeOnDegradedHolderReleasesTicketsAndToleratesMissingPersistence() {
        PluginConfig config = loadDefaults();
        FakeSchedulers schedulers = new FakeSchedulers();
        ChunkTicketService tickets = new ChunkTicketService(schedulers,
                new FakeChunkTicketGateway(), schedulers::currentTick, true, true, message -> { });
        PaperServices holder = new PaperServices(config, true, java.util.Optional.empty(),
                null, "db down", schedulers, tickets, DimensionResolver.from(config.network().dimensions()),
                message -> { });
        holder.close();
        holder.close(); // idempotent: closing twice must not throw
        assertEquals(0, tickets.trackedCount());
    }

    @Test
    void inconsistentPersistenceArgumentsAreRejected() {
        PluginConfig config = loadDefaults();
        FakeSchedulers schedulers = new FakeSchedulers();
        ChunkTicketService tickets = new ChunkTicketService(schedulers,
                new FakeChunkTicketGateway(), schedulers::currentTick, true, false, message -> { });
        DimensionResolver dimensions = DimensionResolver.from(config.network().dimensions());
        // null persistence without a failure reason — the never-half-open invariant
        assertThrows(IllegalArgumentException.class,
                () -> new PaperServices(config, true, java.util.Optional.empty(),
                        null, null, schedulers, tickets, dimensions, message -> { }));
    }
}

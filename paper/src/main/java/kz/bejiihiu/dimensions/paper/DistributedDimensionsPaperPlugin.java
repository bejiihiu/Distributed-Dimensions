package kz.bejiihiu.dimensions.paper;

import kz.bejiihiu.dimensions.common.config.ConfigurationService;
import kz.bejiihiu.dimensions.common.config.EnvLookup;
import kz.bejiihiu.dimensions.common.config.Environment;
import kz.bejiihiu.dimensions.common.config.LoadReport;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.persistence.DimensionPersistence;
import kz.bejiihiu.dimensions.common.persistence.PersistenceFactory;
import kz.bejiihiu.dimensions.common.persistence.PersistenceSettings;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.PlatformSchedulers;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the Paper/Folia backend plugin.
 *
 * <p>Boot order is deliberate: configuration first (everything else reads it), then
 * persistence (schema migrations run inside {@link PersistenceFactory#open} — never
 * against a half-open pool), then the services holder. When any stage fails the
 * plugin <em>stays enabled</em> and enters a fail-closed state: {@link PaperServices}
 * reports {@link HealthReport.Status#DEGRADED} with the reasons, and accessors for
 * persistence throw instead of returning unmigrated state. Later waves must refuse
 * transfers while degraded.
 */
public final class DistributedDimensionsPaperPlugin extends JavaPlugin {

    private PaperServices services;

    @Override
    public void onEnable() {
        Logger logger = getSLF4JLogger();
        long startedAt = System.nanoTime();
        logger.info("Distributed Dimensions booting (Paper/Folia backend)");

        // --- stage 1: configuration (exactly one load) -------------------------
        ConfigurationService configService = new ConfigurationService(
                Environment.resolve(getDataFolder().toPath()), EnvLookup.system(),
                message -> logger.warn("{}", message));
        PluginConfig config;
        try {
            config = configService.load();
        } catch (RuntimeException e) {
            // load() throws only when even the bundled defaults are unusable — there is
            // nothing sensible left to run on. Fail loudly: the plugin stays disabled by
            // the platform (exception propagates and Paper aborts enable).
            logger.error("Configuration is unusable even after fallback to bundled defaults: {}",
                    SecretsRedactor.redact(e.getMessage()));
            throw e;
        }
        boolean configHealthy = configService.isHealthy();
        Optional<String> configFailure = configService.health()
                .map(error -> SecretsRedactor.redact(error.getMessage()));
        logConfigSummary(logger, configService.report(), configHealthy);

        // --- stage 2: dimensions routing (never throws, degraded via reasons) ---
        DimensionResolver dimensions = DimensionResolver.from(config.network().dimensions());
        logDimensions(logger, dimensions);

        // --- stage 3: persistence; migrations run inside PersistenceFactory.open --
        DimensionPersistence persistence = null;
        String persistenceFailure = null;
        try {
            PersistenceSettings persistenceSettings = config.database().toPersistenceSettings();
            persistence = PersistenceFactory.open(persistenceSettings);
            logger.info("persistence ready (engine={}, tablePrefix={}, pool={}/{})",
                    config.database().type().id(), config.database().tablePrefix(),
                    config.database().maximumPoolSize(), config.database().minimumIdle());
        } catch (Throwable t) {
            persistenceFailure = describeFailure(t);
            logger.error("SEVERE: persistence failed to start — entering fail-closed state. "
                    + "The plugin stays enabled; transfers are refused and the journal is "
                    + "unavailable until restart with a fixed database. Cause: {}", persistenceFailure);
        }

        // --- stage 4: schedulers + chunk tickets + holder -----------------------
        Schedulers schedulers = new PlatformSchedulers(this);
        ChunkTicketService chunkTickets = ChunkTicketService.create(this, schedulers,
                config.entities().crossServerTransfer().chunkTickets());
        if (!chunkTickets.enabled()) {
            logger.info("chunk tickets: disabled by configuration (entities.cross-server-transfer.chunk-tickets.enabled)");
        } else {
            logger.info("chunk tickets: enabled, ttl={} ticks, ring-of-8={}",
                    ChunkTicketService.TTL_TICKS, chunkTickets.ringEnabled());
        }

        services = new PaperServices(config, configHealthy, configFailure, persistence,
                persistenceFailure, schedulers, chunkTickets, dimensions, logger::error);
        Bukkit.getServicesManager().register(PaperServices.class, services, this, ServicePriority.Normal);

        if (!services.isHealthy()) {
            logger.error("SEVERE: backend is DEGRADED — {}. Later waves must refuse transfers.",
                    String.join(" | ", services.health().reasons()));
        } else {
            long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
            logger.info("Distributed Dimensions enabled cleanly ({} ms)", tookMs);
        }
    }

    @Override
    public void onDisable() {
        Logger logger = getSLF4JLogger();
        PaperServices current = services;
        services = null;
        Bukkit.getServicesManager().unregisterAll(this);
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                logger.error("error while closing backend services: {}", SecretsRedactor.redact(e.getMessage()));
            }
        }
        logger.info("Distributed Dimensions disabled");
    }

    // ------------------------------------------------------------------ logging

    private static void logConfigSummary(Logger logger, LoadReport report, boolean healthy) {
        if (report == null) {
            logger.warn("configuration: no load report available");
            return;
        }
        logger.info("configuration: source={} file-created={} config-version={} warnings={} notes={}",
                report.source(), report.fileCreated(), report.configVersion(),
                report.warnings().size(), report.notes().size());
        if (report.migratedFrom() >= 0 && report.migratedTo() >= 0) {
            logger.info("configuration: migrated {} -> {}", report.migratedFrom(), report.migratedTo());
        }
        if (!healthy) {
            logger.error("SEVERE: configuration fell back (source={}) — transfers will be refused until the file is fixed; config health: unhealthy",
                    report.source());
        }
    }

    private static void logDimensions(Logger logger, DimensionResolver dimensions) {
        for (Map.Entry<DimensionId, kz.bejiihiu.dimensions.common.config.DimensionTarget> entry : dimensions.routes().entrySet()) {
            logger.info("dimensions: {} -> server '{}'", entry.getKey(), entry.getValue().server());
        }
        if (!dimensions.allCanonicalResolved()) {
            logger.error("SEVERE: not all three dimensions resolve — transfers are refused until fixed");
        }
        for (String warning : dimensions.warnings()) {
            logger.warn("dimensions: {}", warning);
        }
        for (String error : dimensions.errors()) {
            logger.error("dimensions: {}", error);
        }
    }

    private static String describeFailure(Throwable t) {
        String className = t.getClass().getName();
        String message = SecretsRedactor.redact(t.getMessage());
        return className + ": " + message;
    }
}

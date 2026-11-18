package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Typed holder for the sync-integration surface, mirroring {@code PaperServices}:
 * one instance is created in {@code onEnable}, registered in the ServicesManager,
 * and handed to the arrival flow through {@link #get()}. On disable the armed
 * integration is shut down (listeners unregistered, pending waits released) and the
 * holder unregistered.
 *
 * <p>Arrival-flow contract: resolve it once per arrival, call
 * {@link SyncOperations#awaitSync(Player)} and continue from the future — never
 * join it. On {@link SyncTimeoutException} the transfer is failed; this branch
 * dispatches no public events itself.
 */
public final class SyncServices implements AutoCloseable {

    private final SyncIntegration integration;
    private final SyncCoordinator coordinator;
    private final Logger logger;

    private volatile boolean closed;

    SyncServices(SyncIntegration integration, SyncCoordinator coordinator, Logger logger) {
        this.integration = Objects.requireNonNull(integration, "integration");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Production wiring used by the plugin: probes the environment, binds the
     * integration, builds the coordinator, prints the discovery lines, registers
     * the holder in the ServicesManager.
     */
    public static SyncServices bootstrap(JavaPlugin plugin, Schedulers schedulers,
            PluginConfig config, Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(schedulers, "schedulers");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");

        Predicate<UUID> onlineCheck = uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            return player != null && player.isOnline();
        };
        LongSupplier clock = System::currentTimeMillis;

        SyncIntegration integration = SyncIntegrationFactory.bind(
                plugin, schedulers, config.sync(), onlineCheck, clock, logger);
        SyncServices services = new SyncServices(integration,
                new SyncCoordinator(integration, config.sync(), logger), logger);
        Bukkit.getServicesManager().register(
                SyncServices.class, services, plugin, ServicePriority.Normal);
        return services;
    }

    /** The live holder. */
    public static SyncServices get() {
        ServicesManager services = Bukkit.getServicesManager();
        SyncServices holder = services.load(SyncServices.class);
        if (holder == null || holder.closed) {
            throw new IllegalStateException(
                    "Distributed Dimensions sync services are not available (plugin disabled or boot failed)");
        }
        return holder;
    }

    /** True when the plugin is enabled and the holder is usable. */
    public static boolean isAvailable() {
        try {
            ServicesManager services = Bukkit.getServicesManager();
            SyncServices holder = services.load(SyncServices.class);
            return holder != null && !holder.closed;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The armed integration (diagnostics/discovery only — arrival flow uses the coordinator). */
    public SyncIntegration integration() {
        return integration;
    }

    /** The arrival-flow entry point. */
    public SyncOperations coordinator() {
        return coordinator;
    }

    /** Immediate snapshot for health reporting; never throws. */
    public SyncReadiness readiness() {
        return integration.phase();
    }

    @Override
    public void close() {
        closed = true;
        try {
            integration.shutdown();
        } catch (RuntimeException e) {
            logger.warn("sync: error while shutting down the sync integration: {}", e.getMessage());
        }
    }
}

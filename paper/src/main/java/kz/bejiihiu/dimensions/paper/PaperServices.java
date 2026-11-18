package kz.bejiihiu.dimensions.paper;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.DimensionPersistence;
import kz.bejiihiu.dimensions.common.persistence.EntityPayloadStore;
import kz.bejiihiu.dimensions.common.persistence.PortalIndex;
import kz.bejiihiu.dimensions.common.persistence.RespawnPointStore;
import kz.bejiihiu.dimensions.common.persistence.TransferContextStore;
import kz.bejiihiu.dimensions.common.persistence.TransferOperationJournal;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Typed holder for everything the backend exposes to its own feature waves and to
 * API consumers. Looked up via the Bukkit {@link ServicesManager} — never by static
 * reaches into the plugin class.
 *
 * <p><b>Fail-closed.</b> When persistence could not be opened (connection failure,
 * migration failure) the plugin stays enabled but this holder reports
 * {@link HealthReport.Status#DEGRADED} with the reason, and {@link #persistence()},
 * {@link #journal()} and {@link #portalIndex()} throw instead of handing out a
 * half-open instance. Callers in later waves must check {@link #isHealthy()} (or
 * catch the exception) and refuse transfers. There is deliberately no path that
 * yields a journal without migrations run.
 *
 * <p><b>Lifecycle.</b> One instance is created in {@code onEnable}, registered in the
 * ServicesManager, handed to feature waves through {@link #get()}; on disable it is
 * closed (chunk tickets released first, persistence after) and unregistered.
 */
public final class PaperServices implements AutoCloseable {

    private final PluginConfig config;
    private final boolean configHealthy;
    private final Optional<String> configFailure;
    private final DimensionPersistence persistence;      // null when fail-closed
    private final String persistenceFailure;             // null when persistence is up
    private final Schedulers schedulers;
    private final ChunkTicketService chunkTickets;
    private final DimensionResolver dimensions;
    private final HealthReport health;
    private final Consumer<String> diagnostics;

    private volatile boolean closed;

    PaperServices(PluginConfig config, boolean configHealthy, Optional<String> configFailure,
            DimensionPersistence persistence, String persistenceFailure,
            Schedulers schedulers, ChunkTicketService chunkTickets, DimensionResolver dimensions,
            Consumer<String> diagnostics) {
        this.config = Objects.requireNonNull(config, "config");
        this.configHealthy = configHealthy;
        this.configFailure = configFailure == null ? Optional.empty() : configFailure;
        this.persistence = persistence;
        this.persistenceFailure = persistenceFailure;
        if ((persistence == null) != (persistenceFailure != null)) {
            throw new IllegalArgumentException(
                    "persistence and persistenceFailure must be set consistently: "
                            + "persistence == null iff persistenceFailure != null");
        }
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
        this.chunkTickets = Objects.requireNonNull(chunkTickets, "chunkTickets");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.health = computeHealth(configHealthy, this.configFailure, persistenceFailure, dimensions);
    }

    // ------------------------------------------------------------------ lookup

    /**
     * The live holder.
     *
     * @throws IllegalStateException when the plugin is disabled or failed to boot
     */
    public static PaperServices get() {
        ServicesManager services = Bukkit.getServicesManager();
        PaperServices holder = services.load(PaperServices.class);
        if (holder == null || holder.closed) {
            throw new IllegalStateException(
                    "Distributed Dimensions backend services are not available (plugin disabled or boot failed)");
        }
        return holder;
    }

    /** True when the plugin is enabled and the holder is usable. */
    public static boolean isAvailable() {
        try {
            return Bukkit.getServicesManager().load(PaperServices.class) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ config

    /** The loaded configuration snapshot (never a partially built one). */
    public PluginConfig config() {
        return config;
    }

    /** True when the file loaded cleanly (no last-good / bundled-defaults fallback). */
    public boolean configHealthy() {
        return configHealthy;
    }

    /** Present when the configuration fell back; message never contains secret values. */
    public Optional<String> configFailure() {
        return configFailure;
    }

    // ------------------------------------------------------------------ persistence

    /**
     * The opened persistence (journal + portal index).
     *
     * @throws IllegalStateException when the backend is in fail-closed mode
     */
    public DimensionPersistence persistence() {
        DimensionPersistence current = persistence;
        if (current == null) {
            throw new IllegalStateException(
                    "persistence is unavailable (fail-closed): " + persistenceFailure);
        }
        return current;
    }

    /** Transfer-operation journal. Same fail-closed contract as {@link #persistence()}. */
    public TransferOperationJournal journal() {
        return persistence().journal();
    }

    /** Portal index. Same fail-closed contract as {@link #persistence()}. */
    public PortalIndex portalIndex() {
        return persistence().portalIndex();
    }

    /** Respawn-point store. Same fail-closed contract as {@link #persistence()}. */
    public RespawnPointStore respawnPoints() {
        return persistence().respawnPoints();
    }

    /** Entity-transfer payload store. Same fail-closed contract as {@link #persistence()}. */
    public EntityPayloadStore entityPayloads() {
        return persistence().entityPayloads();
    }

    /** Transfer-context store. Same fail-closed contract as {@link #persistence()}. */
    public TransferContextStore transferContexts() {
        return persistence().transferContexts();
    }

    // ------------------------------------------------------------------ services

    /** The scheduler facade — the only scheduling path the plugin may use. */
    public Schedulers schedulers() {
        return schedulers;
    }

    /** Chunk ticket service for portal-arrival chunks. */
    public ChunkTicketService chunkTickets() {
        return chunkTickets;
    }

    /** Configured dimension routing. */
    public DimensionResolver dimensions() {
        return dimensions;
    }

    // ------------------------------------------------------------------ health

    public HealthReport health() {
        return health;
    }

    /** True only when configuration, persistence and dimension routing are all clean. */
    public boolean isHealthy() {
        return health.isHealthy();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() {
        closed = true;
        try {
            chunkTickets.close();
        } catch (RuntimeException e) {
            diagnostics.accept("chunk-ticket cleanup failed on disable: " + e.getMessage());
        }
        if (persistence != null) {
            try {
                persistence.close();
            } catch (RuntimeException e) {
                diagnostics.accept("persistence close failed on disable: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private static HealthReport computeHealth(boolean configHealthy, Optional<String> configFailure,
            String persistenceFailure, DimensionResolver dimensions) {
        List<String> reasons = new ArrayList<>();
        if (!configHealthy) {
            reasons.add("configuration not clean: " + configFailure.orElse("unknown configuration failure"));
        }
        if (persistenceFailure != null) {
            reasons.add("persistence unavailable (fail-closed): " + persistenceFailure);
        }
        for (String problem : dimensions.errors()) {
            reasons.add("dimensions: " + problem);
        }
        return reasons.isEmpty()
                ? HealthReport.healthy()
                : new HealthReport(HealthReport.Status.DEGRADED, reasons);
    }
}

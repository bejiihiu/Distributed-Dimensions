package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.RespawnPointStore;
import kz.bejiihiu.dimensions.common.persistence.TransferOperationJournal;
import kz.bejiihiu.dimensions.paper.DimensionResolver;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Consumer;

/**
 * Production wiring of the transfer orchestration to {@link PaperServices}. Fail-closed
 * is delegated: {@link #isHealthy()} / {@link #healthReasons()} reflect the holder, and
 * the journal/respawn-store accessors throw when the backend is degraded — but no caller
 * on the portal path reaches them while degraded (they check {@link #isHealthy()} first).
 */
public final class PaperTransferBackend implements TransferBackend {

    private final PaperServices services;
    private final TransferEventDispatcher events;
    private final TransferChannel channel;
    private final Consumer<String> diagnostics;
    private final Consumer<String> warn;
    private final Consumer<String> severe;

    public PaperTransferBackend(PaperServices services, TransferEventDispatcher events,
                                TransferChannel channel, JavaPlugin plugin) {
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.channel = java.util.Objects.requireNonNull(channel, "channel");
        this.diagnostics = message -> plugin.getSLF4JLogger().info(message);
        this.warn = message -> plugin.getSLF4JLogger().warn(message);
        this.severe = message -> plugin.getSLF4JLogger().error(message);
    }

    @Override
    public boolean isHealthy() {
        return services.isHealthy();
    }

    @Override
    public List<String> healthReasons() {
        return services.health().reasons();
    }

    @Override
    public PluginConfig config() {
        return services.config();
    }

    @Override
    public DimensionResolver dimensions() {
        return services.dimensions();
    }

    @Override
    public Schedulers schedulers() {
        return services.schedulers();
    }

    @Override
    public TransferOperationJournal journal() {
        return services.journal();
    }

    @Override
    public RespawnPointStore respawnPoints() {
        return services.respawnPoints();
    }

    @Override
    public ChunkTicketService chunkTickets() {
        return services.chunkTickets();
    }

    @Override
    public TransferEventDispatcher events() {
        return events;
    }

    @Override
    public TransferChannel channel() {
        return channel;
    }

    @Override
    public Consumer<String> diagnostics() {
        return diagnostics;
    }

    @Override
    public Consumer<String> warn() {
        return warn;
    }

    @Override
    public Consumer<String> severe() {
        return severe;
    }
}

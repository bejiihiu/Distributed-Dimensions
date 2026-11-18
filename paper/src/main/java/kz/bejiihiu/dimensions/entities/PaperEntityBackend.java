package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.EntityPayloadStore;
import kz.bejiihiu.dimensions.paper.DimensionResolver;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Consumer;

/** Production wiring of the entity flow to {@link PaperServices}. Fail-closed is delegated. */
public final class PaperEntityBackend implements EntityBackend {

    private final PaperServices services;
    private final Consumer<String> diagnostics;

    public PaperEntityBackend(PaperServices services, JavaPlugin plugin) {
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.diagnostics = message -> plugin.getSLF4JLogger().info(message);
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
    public ChunkTicketService chunkTickets() {
        return services.chunkTickets();
    }

    @Override
    public EntityPayloadStore entityPayloads() {
        return services.entityPayloads();
    }

    @Override
    public Consumer<String> diagnostics() {
        return diagnostics;
    }
}

package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.EntityPayloadStore;
import kz.bejiihiu.dimensions.paper.DimensionResolver;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import java.util.List;
import java.util.function.Consumer;

/**
 * The collaborator surface the entity-transfer flow needs — mirrors the transfer
 * orchestration's backend seam. Production wiring ({@link PaperEntityBackend}) goes
 * through {@link PaperServices} with its fail-closed contract; tests hand-write fakes
 * for the same seam.
 */
public interface EntityBackend {

    /** Fail-closed health gate. False = degraded = refuse transfers. */
    boolean isHealthy();

    /** Human-readable health reasons, empty when healthy. */
    List<String> healthReasons();

    PluginConfig config();

    DimensionResolver dimensions();

    Schedulers schedulers();

    ChunkTicketService chunkTickets();

    /** Entity-transfer payload store. Throws in fail-closed mode — check {@link #isHealthy()} first. */
    EntityPayloadStore entityPayloads();

    /** Diagnostics sink: secrets-free lines, info severity. */
    Consumer<String> diagnostics();
}

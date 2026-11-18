package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.RespawnPointStore;
import kz.bejiihiu.dimensions.common.persistence.TransferOperationJournal;
import kz.bejiihiu.dimensions.paper.DimensionResolver;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import java.util.function.Consumer;

/**
 * The collaborator surface the transfer orchestration needs. Production wires this to
 * {@code PaperServices} (plus the channel and dispatcher); tests hand-write fakes for the
 * same seam — no live server, no reflection, no mock frameworks.
 */
public interface TransferBackend {

    /** Fail-closed health gate. False = degraded = refuse transfers. */
    boolean isHealthy();

    /** Human-readable health reasons, empty when healthy (for refuse messages/logs). */
    java.util.List<String> healthReasons();

    PluginConfig config();

    DimensionResolver dimensions();

    Schedulers schedulers();

    TransferOperationJournal journal();

    RespawnPointStore respawnPoints();

    ChunkTicketService chunkTickets();

    TransferEventDispatcher events();

    TransferChannel channel();

    /** Diagnostics sink: info-level lines, secrets-free. */
    Consumer<String> diagnostics();

    /** Warning sink: lines an operator should see even in a healthy log, secrets-free. */
    Consumer<String> warn();

    /** Error sink: terminal failure lines with player identity and reason, secrets-free. */
    Consumer<String> severe();
}

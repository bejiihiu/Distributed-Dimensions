package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.config.ConfigurationService;
import kz.bejiihiu.dimensions.common.config.EnvLookup;
import kz.bejiihiu.dimensions.common.config.Environment;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.persistence.RespawnPointStore;
import kz.bejiihiu.dimensions.common.persistence.TransferOperationJournal;
import kz.bejiihiu.dimensions.common.persistence.InMemoryRespawnPointStore;
import kz.bejiihiu.dimensions.paper.DimensionResolver;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.chunktickets.FakeChunkTicketGateway;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Hand-written fake for the {@link TransferBackend} seam. Configuration is loaded from
 * the bundled defaults into a temp directory (same as the production config tests);
 * the journal is the honest in-memory fake; the channel and dispatcher record calls.
 * No live server, no mocking framework.
 */
public final class FakeTransferBackend implements TransferBackend {

    private final PluginConfig config;
    private final boolean healthy;
    private final DimensionResolver dimensions;
    private final FakeSchedulers schedulers = new FakeSchedulers();
    private final FakeTransferOperationJournal journal = new FakeTransferOperationJournal();
    private RespawnPointStore respawnPoints = new InMemoryRespawnPointStore();
    private final RecordingTransferEventDispatcher events = new RecordingTransferEventDispatcher();
    private final RecordingTransferChannel channel = new RecordingTransferChannel();
    private final ChunkTicketService chunkTickets;
    private final FakeChunkTicketGateway ticketGateway = new FakeChunkTicketGateway();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<String> severes = new CopyOnWriteArrayList<>();

    public FakeTransferBackend(PluginConfig config) {
        this(config, true);
    }

    public FakeTransferBackend(PluginConfig config, boolean healthy) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.healthy = healthy;
        this.dimensions = DimensionResolver.from(config.network().dimensions());
        this.chunkTickets = new ChunkTicketService(
                schedulers, ticketGateway, schedulers::currentTick, true, true, message -> { });
    }

    /** Bundled-defaults configuration in a fresh temp directory. */
    public static PluginConfig loadDefaults(Path tempDir) {
        ConfigurationService service = new ConfigurationService(
                Environment.resolve(tempDir), EnvLookup.fixed(Map.of("DB_PASSWORD", "test")), message -> { });
        return service.load();
    }

    public FakeSchedulers fakeSchedulers() {
        return schedulers;
    }

    public FakeTransferOperationJournal journalFake() {
        return journal;
    }

    public RespawnPointStore respawnStore() {
        return respawnPoints;
    }

    public RecordingTransferEventDispatcher eventRecorder() {
        return events;
    }

    public RecordingTransferChannel channelFake() {
        return channel;
    }

    /** Swaps the respawn store (e.g. for a failing fake in failure-path tests). */
    public void setRespawnPoints(RespawnPointStore store) {
        this.respawnPoints = java.util.Objects.requireNonNull(store, "store");
    }

    public FakeChunkTicketGateway ticketGateway() {
        return ticketGateway;
    }

    public List<String> diagnosticLog() {
        return List.copyOf(diagnostics);
    }

    /** Warning-level lines (degraded-mode visibility). */
    public List<String> warningLog() {
        return List.copyOf(warnings);
    }

    /** Severe-level lines (terminal failure with player identity and reason). */
    public List<String> severeLog() {
        return List.copyOf(severes);
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public List<String> healthReasons() {
        return healthy ? List.of() : List.of("forced unhealthy backend (test)");
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public DimensionResolver dimensions() {
        return dimensions;
    }

    @Override
    public Schedulers schedulers() {
        return schedulers;
    }

    @Override
    public TransferOperationJournal journal() {
        return journal;
    }

    @Override
    public RespawnPointStore respawnPoints() {
        return respawnPoints;
    }

    @Override
    public ChunkTicketService chunkTickets() {
        return chunkTickets;
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
        return diagnostics::add;
    }

    @Override
    public Consumer<String> warn() {
        return warnings::add;
    }

    @Override
    public Consumer<String> severe() {
        return severes::add;
    }
}








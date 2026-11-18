package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.persistence.DuplicateOperationException;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException;
import kz.bejiihiu.dimensions.common.protocol.ProtocolEncodeException;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import kz.bejiihiu.dimensions.api.TransferResult;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * The reference {@link CrossServerTransfer}: journals the operation, dispatches the
 * start event, and shuttles one {@code dimension_transfer_request} to the proxy over
 * the {@value #CHANNEL} plugin-message channel. The terminal outcome arrives as
 * {@code dimension_transfer_result}; a pre-dispatch refusal or a timeout fails the
 * operation locally.
 *
 * <p><b>Flow (source backend).</b>
 *
 * <pre>{@code
 * guard/API call → transfer(command)
 *   → refuse early when the backend is degraded (no events, no journal row)
 *   → mint operation id; DimensionTransferStartEvent (cancellable) — a cancelled start
 *     vetoes the operation and produces no DimensionTransferFailedEvent, per the api contract
 *   → journal CREATED → CAS to PREPARING
 *   → encode + send DimensionTransferRequestMessage on the player's connection
 *   → arm the timeout task (network.transfer.timeout, MILLISECONDS)
 * proxy answers on the same channel → onPluginMessage
 *   → ok=true: CAS PREPARING→COMMITTED (source-side handoff semantics; the target backend
 *     owns the materialization and fires CompleteEvent)
 *   → ok=false: CAS PREPARING→FAILED(error) + DimensionTransferFailedEvent (source)
 * timeout without a result → CAS PREPARING→FAILED(TIMEOUT) + DimensionTransferFailedEvent
 * }</pre>
 *
 * <p><b>Journal semantics.</b> The source advances its own row to COMMITTED when the proxy
 * confirms the switch — its obligations end at the handoff. Materialization on the target is
 * the target backend's business; it keeps its own row keyed by the same operation id. Callers
 * observing {@code status()} on the source see COMMITTED once the proxy accepted the switch,
 * not when the player is physically inside the target world.
 *
 * <p><b>Threads.</b> {@code transfer()} runs on the caller's thread (for mirage guards: the
 * teleport-event region thread). The request is encoded and written inline (plugin messages are
 * cheap and thread-safe). The timeout and the result handling touch the journal from the async
 * scheduler — the journal is a blocking connection pool and must never run on a region thread.
 * Failed events dispatch on the global scheduler (never on a region thread, never with the
 * possibly-dead player as the owning thread).
 *
 * <p><b>Limitations.</b> {@code source_server} is not configured anywhere (the configuration
 * records are frozen for this release); it is resolved via {@link SourceServerName}. Without it
 * the backend cannot produce a request the proxy will accept, and every transfer fails closed
 * with UNAVAILABLE — loudly, never silently.
 */
public final class ChannelCrossServerTransfer implements CrossServerTransfer, PluginMessageListener {

    /** The one channel both sides speak; the proxy registers it the same way. */
    public static final String CHANNEL = "distributeddimensions:transfer";

    private final JavaPlugin plugin;
    private final PaperServices services;
    private final Schedulers schedulers;
    private final Logger logger;
    private final long timeoutMillis;

    private final Map<UUID, PendingTransfer> pending = new ConcurrentHashMap<>();
    private final LongAdder dispatched = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder timedOut = new LongAdder();

    public ChannelCrossServerTransfer(@NotNull JavaPlugin plugin, @NotNull PaperServices services) {
        this.plugin = plugin;
        this.services = services;
        this.schedulers = services.schedulers();
        this.logger = plugin.getSLF4JLogger();
        this.timeoutMillis = Math.max(1_000L, services.config().network().transfer().timeout().toMillis());
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    // ------------------------------------------------------------- sending

    @Override
    public @NotNull TransferResult transfer(@NotNull TransferCommand command) {
        UUID operationId = UUID.randomUUID();
        Player player = command.player();
        Instant now = Instant.now();

        // 0. fail-closed: never run a transfer on a degraded backend
        if (!services.isHealthy()) {
            refused.increment();
            logger.error("SEVERE: refusing transfer of player {} (target {}): backend is DEGRADED — {}",
                    player.getUniqueId(), command.targetDimension(), healthSummary());
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNAVAILABLE,
                    "backend is degraded; transfers are refused");
        }

        String sourceServer = SourceServerName.resolve(services.config().network().sourceServer());
        if (sourceServer == null) {
            refused.increment();
            logger.error("SEVERE: refusing transfer of player {} (target {}): the backend does not know its "
                            + "own server name (set network.source-server in config.yml, or {} / {})",
                    player.getUniqueId(), command.targetDimension(), SourceServerName.ENV_VAR, SourceServerName.SYSTEM_PROPERTY);
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNAVAILABLE,
                    "backend does not know its proxy server name (see logs)");
        }

        Optional<String> targetServer = services.dimensions().serverFor(command.targetDimension());
        if (targetServer.isEmpty()) {
            refused.increment();
            logger.error("refusing transfer of player {}: target dimension {} is not configured in network.dimensions",
                    player.getUniqueId(), command.targetDimension());
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNAVAILABLE,
                    "target dimension '" + command.targetDimension().key() + "' is not configured on this backend");
        }

        // 1. where are we coming from? environment → canonical dimension
        FromDimension.Resolution from = FromDimension.of(environmentOf(player));
        if (!from.canonical()) {
            logger.warn("transfer {}: source dimension is not canonical (environment={}); "
                    + "reporting minecraft:overworld in the start event", operationId, environmentOf(player));
        }

        // 2. start event — cancellable; a cancelled start vetoes the operation entirely
        boolean startCancelled = TransferEvents.dispatchStart(operationId, player,
                InteropMapping.apiDimension(from.dimension()),
                InteropMapping.apiDimension(command.targetDimension()),
                InteropMapping.apiCause(command.cause()));
        if (startCancelled) {
            refused.increment();
            logger.info("transfer {} of player {} vetoed by DimensionTransferStartEvent listener",
                    operationId, player.getUniqueId());
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.REJECTED,
                    "cancelled by DimensionTransferStartEvent listener");
        }

        // 3. journal CREATED then CAS to PREPARING — from here on the operation exists
        TransferOperation created = TransferOperation.created(operationId, player.getUniqueId(),
                sourceServer, targetServer.get(), from.dimension(), command.targetDimension(),
                command.arrival(), command.cause(), now);
        try {
            services.journal().recordCreated(created);
        } catch (DuplicateOperationException e) {
            // a duplicate operation id this fresh would mean a broken UUID source; fail closed
            refused.increment();
            logger.error("SEVERE: duplicate operation id {}: {}", operationId, e.getMessage());
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNKNOWN,
                    "duplicate operation id");
        } catch (RuntimeException e) {
            refused.increment();
            logger.error("SEVERE: failed to journal operation {}: {}", operationId, e.toString());
            dispatchFailedSafely(operationId, player, from.dimension(), command,
                    TransferError.UNAVAILABLE, OperationState.CREATED);
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNAVAILABLE,
                    "persistence unavailable");
        }

        boolean prepared = cas(created, OperationState.PREPARING, null, null);
        if (!prepared) {
            refused.increment();
            failAndNotify(operationId, player, from.dimension(), command, OperationState.CREATED,
                    TransferError.UNAVAILABLE, "journal compare-and-set failed");
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNAVAILABLE,
                    "journal compare-and-set failed");
        }

        // 4. send the request on the player's connection
        DimensionTransferRequestMessage message = new DimensionTransferRequestMessage(
                operationId, player.getUniqueId(), sourceServer, targetServer.get(),
                from.dimension(), command.targetDimension(), command.arrival(), command.cause());
        final byte[] payload;
        try {
            payload = ProtocolCodec.encodeToBytes(message);
        } catch (ProtocolEncodeException e) {
            failAndNotify(operationId, player, from.dimension(), command, OperationState.PREPARING,
                    TransferError.UNKNOWN, "failed to encode transfer request: " + e.getMessage());
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNKNOWN,
                    "failed to encode transfer request");
        }
        try {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        } catch (RuntimeException e) {
            failAndNotify(operationId, player, from.dimension(), command, OperationState.PREPARING,
                    TransferError.UNKNOWN, "failed to send plugin message: " + e);
            return TransferResult.failure(operationId, kz.bejiihiu.dimensions.api.TransferError.UNKNOWN,
                    "failed to send transfer request");
        }

        // 5. arm the timeout and report acceptance
        pending.put(operationId, new PendingTransfer(operationId, player,
                from.dimension(), command.targetDimension(), command.arrival(), command.cause(), now.toEpochMilli()));
        schedulers.runAsyncDelayedFor(timeoutMillis, TimeUnit.MILLISECONDS,
                () -> onTimeout(operationId));
        dispatched.increment();
        logger.debug("transfer {}: player {} → {} via server '{}' (cause={})",
                operationId, player.getUniqueId(), command.targetDimension(), targetServer.get(), command.cause());
        return TransferResult.success(operationId);
    }

    // ------------------------------------------------------------- receiving

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player carrier, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        final DimensionTransferResultMessage result;
        try {
            var decoded = ProtocolCodec.decode(message);
            if (!(decoded instanceof DimensionTransferResultMessage r)) {
                logger.debug("dropping unrelated {} message of type {}", CHANNEL, decoded.type());
                return;
            }
            result = r;
        } catch (ProtocolDecodeException e) {
            logger.warn("dropping undecodable {} message: {}", CHANNEL, e.getMessage());
            return;
        }

        PendingTransfer waiting = pending.remove(result.requestId());
        if (waiting == null) {
            logger.debug("transfer {}: proxy result with no in-flight operation (already timed out?) — ignoring",
                    result.requestId());
            return;
        }

        // DB work off the main thread; the event goes to the global scheduler
        schedulers.runAsync(() -> finishOperation(waiting, result));
    }

    private void finishOperation(PendingTransfer waiting, DimensionTransferResultMessage result) {
        Optional<TransferOperation> current = journalFindById(waiting.operationId());
        if (current.isEmpty()) {
            logger.warn("transfer {}: journal row vanished before completion; dispatching failure to be safe",
                    waiting.operationId());
            dispatchFailed(waiting, TransferError.UNKNOWN, OperationState.PREPARING);
            failed.increment();
            return;
        }
        if (result.ok()) {
            current.ifPresent(op -> cas(op, OperationState.COMMITTED, null, null));
            // the target backend materializes the player and fires CompleteEvent per its own pipeline
            accepted.increment();
            logger.debug("transfer {}: proxy confirmed the switch (player {})", waiting.operationId(), waiting.playerUuid());
            return;
        }
        TransferError error = result.error() != null ? result.error() : TransferError.UNKNOWN;
        current.ifPresent(op -> cas(op, OperationState.FAILED, error,
                result.message() != null ? result.message() : "proxy reported failure"));
        dispatchFailed(waiting, error, OperationState.PREPARING);
        failed.increment();
    }

    private void onTimeout(UUID operationId) {
        PendingTransfer waiting = pending.remove(operationId);
        if (waiting == null) {
            return; // resolved in time
        }
        timedOut.increment();
        logger.warn("transfer {}: no proxy result within {} ms; failing the operation (player {})",
                operationId, timeoutMillis, waiting.playerUuid());
        schedulers.runAsync(() -> journalFindById(operationId)
                .ifPresent(op -> cas(op, OperationState.FAILED, TransferError.TIMEOUT, "transfer timed out")));
        dispatchFailed(waiting, TransferError.TIMEOUT, OperationState.PREPARING);
    }

    // ------------------------------------------------------------- helpers

    private void dispatchFailed(PendingTransfer waiting, TransferError error, OperationState phase) {
        boolean playerOnline = Bukkit.getPlayer(waiting.playerUuid()) != null;
        schedulers.runGlobal(() -> TransferEvents.dispatchFailed(waiting.operationId(),
                waiting.player(),
                InteropMapping.apiDimension(waiting.fromDimension()),
                InteropMapping.apiDimension(waiting.toDimension()),
                InteropMapping.apiCause(waiting.cause()),
                InteropMapping.apiTransferError(error),
                InteropMapping.apiOperationState(phase),
                playerOnline));
    }

    private void dispatchFailedSafely(UUID operationId, Player player, DimensionId from,
                                      TransferCommand command, TransferError error, OperationState phase) {
        // probed before dispatching: the event goes out on the global region thread and the
        // player reference may not be touched from there; the registry lookup is thread-safe
        boolean playerOnline = player != null && Bukkit.getPlayer(player.getUniqueId()) != null;
        schedulers.runGlobal(() -> TransferEvents.dispatchFailed(operationId, player,
                InteropMapping.apiDimension(from),
                InteropMapping.apiDimension(command.targetDimension()),
                InteropMapping.apiCause(command.cause()),
                InteropMapping.apiTransferError(error),
                InteropMapping.apiOperationState(phase),
                playerOnline));
    }

    private void failAndNotify(UUID operationId, Player player, DimensionId from,
                               TransferCommand command, OperationState phase,
                               TransferError error, String logMessage) {
        failed.increment();
        logger.error("SEVERE: transfer {}: {}", operationId, logMessage);
        schedulers.runAsync(() -> journalFindById(operationId)
                .ifPresent(op -> cas(op, OperationState.FAILED, error, logMessage)));
        dispatchFailedSafely(operationId, player, from, command, error, phase);
    }

    private Optional<TransferOperation> journalFindById(UUID operationId) {
        try {
            return services.journal().findById(operationId);
        } catch (RuntimeException e) {
            logger.error("journal lookup for operation {} failed: {}", operationId, e.toString());
            return Optional.empty();
        }
    }

    private boolean cas(TransferOperation current, OperationState target,
                        TransferError error, String message) {
        try {
            Instant now = Instant.now();
            TransferOperation updated = current.withState(target, now, error, message);
            return services.journal().compareAndSetState(current.operationId(), current.state(), updated);
        } catch (RuntimeException e) {
            logger.error("journal compare-and-set {} → {} for operation {} failed: {}",
                    current.state(), target, current.operationId(), e.toString());
            return false;
        }
    }

    private World.Environment environmentOf(Player player) {
        try {
            return player.getWorld().getEnvironment();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String healthSummary() {
        try {
            return String.join(" | ", services.health().reasons());
        } catch (RuntimeException e) {
            return "unknown (health probe failed: " + e + ")";
        }
    }

    // ------------------------------------------------------------- diagnostics

    /** Operational counters, for diagnostics and tests. */
    public long dispatched() {
        return dispatched.sum();
    }

    public long accepted() {
        return accepted.sum();
    }

    public long refused() {
        return refused.sum();
    }

    public long failed() {
        return failed.sum();
    }

    public long timedOut() {
        return timedOut.sum();
    }

    /** In-flight operations (should decay to 0 within the timeout). */
    public int pendingCount() {
        return pending.size();
    }
}


package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException;
import kz.bejiihiu.dimensions.common.protocol.ProtocolEncodeException;
import kz.bejiihiu.dimensions.common.protocol.ProtocolMessage;
import kz.bejiihiu.dimensions.common.util.IdGenerator;

import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Source side of a portal-initiated cross-server transfer. One code path serves the End
 * entry portal, the End exit portal and the Nether portal(s) the Nether wave will wire —
 * the callers differ only in (from, to, cause, arrival position).
 *
 * <p>Threading: {@link #transfer} is called from the triggering portal block's region
 * thread (Folia) or the main thread (Paper) — the same context the portal event fired
 * in — because the Start event contract requires the triggering thread. Persistence
 * calls on a warm pool are sub-millisecond; the proxy round-trip and the watchdog run
 * asynchronously. Proxy results are handed off to the async scheduler immediately.
 *
 * <p>No failure path ever teleports the player, detaches them halfway, or fabricates a
 * completed operation. Fail-closed at every step; the journal row is the audit trail.
 */
public final class PortalTransferService implements TransferChannelSink {

    /** Result of attempting to start a transfer. */
    public sealed interface Outcome {
        /** The request was handed to the proxy; the player will be switched. */
        record Started(UUID operationId) implements Outcome {
        }

        /**
         * The transfer was refused before any dispatch (degraded backend, unroutable
         * dimension, persistence failure, oversize message). The portal stayed cancelled,
         * the player is untouched, no Start event fired.
         */
        record Refused(String reason) implements Outcome {
        }

        /** A listener cancelled the Start event. No request was sent; no failure event fired. */
        record Cancelled(UUID operationId) implements Outcome {
        }
    }

    private final TransferBackend backend;
    private final Clock clock;
    private final TransferRouteTracker tracker = new TransferRouteTracker();

    public PortalTransferService(TransferBackend backend, Clock clock) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs on the triggering portal's region thread. The caller has already cancelled the
     * portal event — vanilla cannot cross servers, so the event is never allowed to stand.
     */
    public Outcome transfer(Player player, DimensionId from, DimensionId to,
                            TransferCause cause, Position arrival) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        if (!backend.isHealthy()) {
            return new Outcome.Refused("backend is degraded ("
                    + String.join("; ", backend.healthReasons()) + ")");
        }
        Optional<String> targetServer = backend.dimensions().serverFor(to);
        if (targetServer.isEmpty()) {
            return new Outcome.Refused("dimension " + to.key()
                    + " has no backend configured on this network (dimensions.* misconfigured)");
        }
        Optional<String> sourceServer = backend.dimensions().serverFor(from);
        if (sourceServer.isEmpty()) {
            return new Outcome.Refused("this server is not configured to serve dimension " + from.key()
                    + " — refusing to claim it as the source");
        }

        UUID operationId = IdGenerator.newId();
        Instant created = Instant.now(clock);
        TransferOperation operation = TransferOperation.created(
                operationId, player.getUniqueId(),
                sourceServer.get(), targetServer.get(),
                from, to, arrival, cause, created);

        try {
            backend.journal().recordCreated(operation);
        } catch (kz.bejiihiu.dimensions.common.persistence.DuplicateOperationException e) {
            return new Outcome.Refused("operation id collision, please retry");
        } catch (RuntimeException e) {
            return new Outcome.Refused("persistence failure, transfer aborted: " + e.getMessage());
        }

        boolean cancelled = backend.events().fireStart(operationId, player, from, to, cause);
        if (cancelled) {
            // cancelled start is NOT a failure (api contract) — mark the row FAILED for hygiene only
            failRowQuietly(operationId, TransferError.REJECTED,
                    "cancelled by DimensionTransferStartEvent listener");
            return new Outcome.Cancelled(operationId);
        }

        Instant preparingAt = Instant.now(clock);
        TransferOperation preparing = operation.withState(OperationState.PREPARING, preparingAt, null, null);
        if (!backend.journal().compareAndSetState(operationId, OperationState.CREATED, preparing)) {
            failRowQuietly(operationId, TransferError.UNKNOWN, "journal moved before dispatch");
            return new Outcome.Refused("operation journal race, transfer aborted");
        }

        byte[] payload;
        try {
            payload = ProtocolCodec.encodeToBytes(new DimensionTransferRequestMessage(
                    operationId, player.getUniqueId(),
                    operation.sourceServer(), operation.targetServer(),
                    from, to, arrival, cause));
        } catch (ProtocolEncodeException e) {
            markPreparedFailed(preparing, TransferError.UNKNOWN,
                    "failed to encode transfer request: " + e.getMessage());
            backend.events().fireFailed(operationId, player, from, to, cause,
                    TransferError.UNKNOWN, OperationState.PREPARING, player.isOnline());
            return new Outcome.Refused("failed to encode the transfer request, transfer aborted");
        }

        tracker.track(new TransferPending(operationId, player, from, to, cause, operation.createdAt()));

        try {
            backend.channel().send(player, payload);
        } catch (RuntimeException e) {
            markPreparedFailed(preparing, TransferError.UNAVAILABLE,
                    "failed to send transfer request to proxy: " + e.getMessage());
            tracker.drop(operationId);
            backend.events().fireFailed(operationId, player, from, to, cause,
                    TransferError.UNAVAILABLE, OperationState.PREPARING, player.isOnline());
            return new Outcome.Refused("proxy channel unavailable, transfer aborted");
        }

        scheduleWatchdog(operationId);
        return new Outcome.Started(operationId);
    }

    // ------------------------------------------------------------ proxy results

    @Override
    public void onMessage(Player carrier, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        backend.schedulers().runAsync(() -> processProxyResult(carrier, payload));
    }

    private void processProxyResult(Player carrier, byte[] payload) {
        ProtocolMessage message;
        try {
            message = ProtocolCodec.decode(payload);
        } catch (ProtocolDecodeException e) {
            backend.diagnostics().accept("dropping undecodable message on transfer channel: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            backend.diagnostics().accept("dropping malformed message on transfer channel: " + e.getMessage());
            return;
        }
        if (!(message instanceof DimensionTransferResultMessage result)) {
            backend.diagnostics().accept("ignoring non-result message of type " + message.type());
            return;
        }

        TransferOperation op;
        try {
            op = backend.journal().findById(result.requestId()).orElse(null);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("failed to read journal for operation " + result.requestId()
                    + ": " + e.getMessage());
            return;
        }
        if (op == null) {
            backend.diagnostics().accept("proxy result for unknown operation " + result.requestId());
            return;
        }
        if (op.state().isTerminal()) {
            backend.diagnostics().accept("late proxy result for terminal operation " + op.operationId()
                    + " (state=" + op.state().wireName() + "), dropping");
            tracker.drop(op.operationId());
            return;
        }
        if (result.ok()) {
            backend.diagnostics().accept("operation " + op.operationId() + " accepted by proxy");
            return;
        }

        TransferError error = result.error() != null ? result.error() : TransferError.UNKNOWN;
        String detail = result.message();
        Instant failedAt = Instant.now(clock);
        TransferOperation failed = op.withState(OperationState.FAILED, failedAt, error,
                detail == null ? "proxy reported a failed transfer" : detail);
        if (!backend.journal().compareAndSetState(op.operationId(), op.state(), failed)) {
            backend.diagnostics().accept("operation " + op.operationId()
                    + " was terminated by another path while the proxy result was processed");
            tracker.drop(op.operationId());
            return;
        }

        Optional<TransferPending> tracked = tracker.peek(op.operationId(), failedAt);
        Player subject = tracked.map(TransferPending::player).orElse(null);
        boolean playerOnline = subject != null && subject.isOnline();
        OperationState phase = op.state();
        tracker.drop(op.operationId());

        // the api contract wants the dispatch synchronous on a reasonable thread; the global region is ours
        backend.schedulers().runGlobal(() -> {
            notifyPlayer(subject, error, detail);
            backend.events().fireFailed(
                    op.operationId(), subject, op.sourceDimension(), op.targetDimension(), op.cause(),
                    error, phase, playerOnline);
        });
    }

    // ------------------------------------------------------------ helpers

    private void scheduleWatchdog(UUID operationId) {
        long timeoutMs = backend.config().network().transfer().timeout().toMillis();
        long delayMs = Math.max(1L, timeoutMs * 2);
        backend.schedulers().runAsyncDelayedFor(delayMs, TimeUnit.MILLISECONDS,
                () -> watchdogCheck(operationId));
    }

    /**
     * Terminal check: a transfer that never reached a terminal state (proxy never answered,
     * or the target died before claiming) is failed as TIMEOUT. Runs async.
     */
    void watchdogCheck(UUID operationId) {
        TransferOperation op;
        try {
            op = backend.journal().findById(operationId).orElse(null);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("watchdog could not read operation " + operationId
                    + " (" + e.getMessage() + ") — leaving the row alone");
            return;
        }
        if (op == null || op.state().isTerminal()) {
            return;
        }
        Instant failedAt = Instant.now(clock);
        TransferOperation failed = op.withState(OperationState.FAILED, failedAt,
                TransferError.TIMEOUT,
                "operation did not reach a terminal state within 2x the configured transfer timeout");
        if (!backend.journal().compareAndSetState(operationId, op.state(), failed)) {
            // another party terminated it between the read and the CAS — that path dispatched the event
            return;
        }
        Optional<TransferPending> tracked = tracker.peek(operationId, failedAt);
        Player subject = tracked.map(TransferPending::player).orElse(null);
        boolean playerOnline = subject != null && subject.isOnline();
        OperationState phase = op.state();
        tracker.drop(operationId);
        backend.schedulers().runGlobal(() -> {
            notifyPlayer(subject, TransferError.TIMEOUT,
                    "the trip took too long and was cancelled");
            backend.events().fireFailed(
                    operationId, subject, op.sourceDimension(), op.targetDimension(), op.cause(),
                    TransferError.TIMEOUT, phase, playerOnline);
        });
    }

    /**
     * Best-effort player-facing notice on the asynchronous failure paths. i18n comes in a
     * later wave; English only. Deliberately quiet when the subject is offline — nothing
     * useful can be said to them.
     */
    private void notifyPlayer(Player subject, TransferError error, String detail) {
        if (subject == null || !subject.isOnline()) {
            return;
        }
        String reason = detail == null || detail.isBlank() ? error.wireName() : detail;
        subject.sendMessage(net.kyori.adventure.text.Component.text(
                "Your trip failed: " + reason + ". You are still here — please try again or tell an administrator."));
    }

    private void failRowQuietly(UUID operationId, TransferError error, String detail) {
        try {
            backend.journal().findById(operationId).ifPresent(op -> {
                if (!op.state().isTerminal()) {
                    TransferOperation failed = op.withState(OperationState.FAILED,
                            Instant.now(clock), error, detail);
                    backend.journal().compareAndSetState(operationId, op.state(), failed);
                }
            });
        } catch (RuntimeException e) {
            backend.diagnostics().accept("failed to record quiet failure for operation " + operationId
                    + ": " + e.getMessage());
        }
    }

    private void markPreparedFailed(TransferOperation preparing, TransferError error, String detail) {
        try {
            TransferOperation failed = preparing.withState(OperationState.FAILED,
                    Instant.now(clock), error, detail);
            backend.journal().compareAndSetState(preparing.operationId(), OperationState.PREPARING, failed);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("failed to record failure for operation " + preparing.operationId()
                    + ": " + e.getMessage());
        }
    }
}

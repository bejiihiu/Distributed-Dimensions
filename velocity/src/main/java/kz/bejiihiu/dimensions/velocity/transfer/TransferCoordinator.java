package kz.bejiihiu.dimensions.velocity.transfer;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.common.protocol.ProtocolEncodeException;
import kz.bejiihiu.dimensions.velocity.DegradedReasons;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Owns the transfer request lifecycle: fail-closed config gate, validation pipeline,
 * idempotency gate, connection attempt with an outer timeout, outcome caching, and
 * delivery of the {@code dimension_transfer_result} back to the requesting backend over
 * the very {@link ServerConnection} the request arrived on.
 *
 * <p>All public methods are safe to call from event-bus threads; the connect itself is
 * asynchronous and the completion handler never blocks. The proxy keeps everything in
 * memory - after a proxy restart, operations the backends still consider in-flight are
 * reconciled by the backends against their own (durable) state, not here.
 *
 * <p>When the proxy is in degraded (fail-closed) mode, the {@code degradedReasonSupplier}
 * is consulted for the cause of the broken state; its output is sanitized before it reaches
 * logs or the wire. The supplier may return null when no cause is known.
 */
public final class TransferCoordinator {

    private final TransferPlatform platform;
    private final TransferOperations operations;
    private final TransferValidator validator;
    private final BooleanSupplier routingHealthy;
    private final Supplier<DimensionsSettings> dimensionsSupplier;
    private final Supplier<Duration> timeoutSupplier;
    private final Supplier<String> degradedReasonSupplier;
    private final Logger logger;
    private final ChannelIdentifier channel;

    public TransferCoordinator(TransferPlatform platform,
                               TransferOperations operations,
                               BooleanSupplier routingHealthy,
                               Supplier<DimensionsSettings> dimensionsSupplier,
                               Supplier<Duration> timeoutSupplier,
                               Supplier<String> degradedReasonSupplier,
                               Logger logger,
                               ChannelIdentifier channel) {
        this.platform = platform;
        this.operations = operations;
        this.validator = new TransferValidator(platform);
        this.routingHealthy = routingHealthy;
        this.dimensionsSupplier = dimensionsSupplier;
        this.timeoutSupplier = timeoutSupplier;
        this.degradedReasonSupplier = degradedReasonSupplier;
        this.logger = logger;
        this.channel = channel;
    }

    /** Entry point for a decoded {@code dimension_transfer_request}. Never throws. */
    public void handleTransferRequest(DimensionTransferRequestMessage message, ServerConnection source) {
        operations.stats().requestsReceived.incrementAndGet();
        String rid = shortId(message.requestId());
        try {
            // 0. fail-closed: never route on a broken configuration half-loaded
            if (!isRoutingHealthy()) {
                operations.stats().refusedBrokenConfig.incrementAndGet();
                String reason = degradedReason();
                String resultMessage = reason == null
                        ? "proxy configuration is broken; transfers are disabled"
                        : "proxy configuration is broken: " + reason
                                + "; transfers are disabled until '/ddproxy reload' reports a clean load";
                if (reason != null) {
                    logger.error("refusing transfer {} from server '{}': proxy configuration is broken: {} - "
                            + "transfers are disabled until '/ddproxy reload' reports a clean load",
                            rid, message.sourceServer(), reason);
                } else {
                    logger.error("refusing transfer {} from server '{}': proxy configuration is broken, "
                            + "transfers are disabled until the configuration loads cleanly", rid, message.sourceServer());
                }
                sendResult(source, message.requestId(), TransferOutcome.failed(TransferError.UNAVAILABLE, resultMessage));
                return;
            }

            // 1-4. validation pipeline
            DimensionsSettings dimensions;
            try {
                dimensions = dimensionsSupplier.get();
                // reading the timeout eagerly would be ambiguous about failure mode; dimensions only here
                if (dimensions == null) {
                    throw new IllegalStateException("dimensions supplier returned null");
                }
            } catch (RuntimeException e) {
                logger.error("refusing transfer {}: unable to read configuration ({})", rid, e.toString());
                sendResult(source, message.requestId(), TransferOutcome.failed(TransferError.UNAVAILABLE,
                        "proxy configuration is unavailable"));
                return;
            }

            ValidationOutcome verdict = validator.validate(message, source, dimensions);
            if (verdict instanceof ValidationOutcome.Failed failed) {
                operations.stats().validationFailures.incrementAndGet();
                logger.warn("rejected transfer {} (source server '{}'): {}", rid, message.sourceServer(), failed.reason());
                sendResult(source, message.requestId(),
                        TransferOutcome.failed(failed.error(), failed.reason()));
                return;
            }

            // 5. idempotency gate
            TransferOperations.BeginResult begin = operations.begin(message.requestId(), message.playerUuid(),
                    message.sourceServer(), message.targetServer(), source);
            switch (begin.status()) {
                case IN_FLIGHT -> {
                    // the in-flight operation owns this id; its outcome will be delivered on that path
                    logger.debug("transfer {} already in flight; not issuing a second connection request", rid);
                    return;
                }
                case REPLAY -> {
                    logger.debug("transfer {} already completed; replaying the cached outcome", rid);
                    sendResult(source, message.requestId(), begin.replay());
                    return;
                }
                case CONFLICT -> {
                    logger.warn("transfer {} rejected: request id replays with different player/server coordinates", rid);
                    sendResult(source, message.requestId(), TransferOutcome.failed(TransferError.REJECTED,
                            "request id was already used by a different transfer operation"));
                    return;
                }
                case FRESH -> {
                    // proceed
                }
            }

            // resolve the target again: validation passed, but a /ddproxy reload or an admin
            // may have unregistered the server between validation and connect
            Optional<RegisteredServer> target = platform.findServer(message.targetServer());
            if (target.isEmpty()) {
                operations.complete(message.requestId(), TransferOutcome.failed(TransferError.BACKEND_DOWN,
                        "target server '" + message.targetServer() + "' is configured but not registered in this proxy"));
                sendResult(source, message.requestId(), TransferOutcome.failed(TransferError.BACKEND_DOWN,
                        "target server '" + message.targetServer() + "' is configured but not registered in this proxy"));
                return;
            }

            Duration timeout = transferTimeout();
            platform.connectTo(message.playerUuid(), target.get())
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((result, error) -> completeOperation(message, source, timeout, result, error));
        } catch (RuntimeException e) {
            // last-ditch guard: a transfer request must never take the listener down
            logger.error("unexpected failure while handling transfer {}: {}", rid, e.toString());
            operations.complete(message.requestId(),
                    TransferOutcome.failed(TransferError.UNKNOWN, "internal proxy error"));
            try {
                sendResult(source, message.requestId(),
                        TransferOutcome.failed(TransferError.UNKNOWN, "internal proxy error"));
            } catch (RuntimeException sendError) {
                logger.error("failed to send error result for {}: {}", rid, sendError.toString());
            }
        }
    }

    /**
     * Sends a failure result to the requesting backend for a message that failed before the
     * transfer pipeline could own it (e.g. an unsupported protocol version). Best-effort: the
     * correlation id here is extracted from an untrusted envelope, not from a validated message.
     */
    public void sendProtocolError(ServerConnection source, UUID requestId, TransferError error, String message) {
        operations.stats().errorRepliesSent.incrementAndGet();
        sendResult(source, requestId, TransferOutcome.failed(error, message));
    }

    /**
     * Marks all in-flight operations of a disconnecting player abandoned. Called from the
     * disconnect listener; nothing is re-kicked and no message is forced onto a dead connection.
     */
    public int abandonPlayer(UUID playerUuid) {
        return operations.abandonPlayer(playerUuid);
    }

    // ------------------------------------------------------------------ internals

    private void completeOperation(DimensionTransferRequestMessage message, ServerConnection source,
                                   Duration timeout,
                                   ConnectionRequestBuilder.Result result, Throwable error) {
        String rid = shortId(message.requestId());
        TransferOutcome outcome = outcomeFrom(message, timeout, result, error);
        Optional<TransferOperations.PendingOperation> pending = operations.complete(message.requestId(), outcome);
        if (pending.isEmpty()) {
            // already abandoned (player disconnected or rolled back); don't touch the connection
            logger.debug("transfer {} completed after being abandoned; outcome was {}", rid,
                    outcome.ok() ? "success" : "failure (" + outcome.error() + ")");
            return;
        }
        if (outcome.ok()) {
            operations.stats().connectsSucceeded.incrementAndGet();
        } else if (outcome.error() == TransferError.TIMEOUT) {
            operations.stats().connectsTimedOut.incrementAndGet();
        } else {
            operations.stats().connectsFailed.incrementAndGet();
        }
        sendResult(source, message.requestId(), outcome);
    }

    private TransferOutcome outcomeFrom(DimensionTransferRequestMessage message, Duration timeout,
                                        ConnectionRequestBuilder.Result result, Throwable error) {
        if (error != null) {
            Throwable cause = unwrap(error);
            if (cause instanceof TimeoutException) {
                return TransferOutcome.failed(TransferError.TIMEOUT,
                        "no response from target server '" + message.targetServer() + "' within " + timeout.toMillis() + "ms");
            }
            if (cause instanceof TransferPlayerGoneException) {
                return TransferOutcome.failed(TransferError.DISCONNECTED,
                        "player " + message.playerUuid() + " is no longer online");
            }
            if (cause instanceof CancellationException) {
                return TransferOutcome.failed(TransferError.UNAVAILABLE,
                        "connection attempt was cancelled before it finished");
            }
            return TransferOutcome.failed(TransferError.BACKEND_DOWN,
                    "target server '" + message.targetServer() + "' is unreachable (" + cause.getClass().getSimpleName() + ")");
        }
        return switch (result.getStatus()) {
            case SUCCESS -> TransferOutcome.ok("transfer complete");
            // already on the requested server: the duplicate request is a no-op success
            case ALREADY_CONNECTED -> TransferOutcome.ok("already connected to '" + message.targetServer() + "'");
            // a connection attempt for this player is already running (e.g. a double portal use);
            // report success-ish and let the in-flight connection do its work
            case CONNECTION_IN_PROGRESS -> {
                logger.debug("transfer {}: velocity reports a connection already in progress for this player; "
                        + "reporting success-ish without issuing a new connect", shortId(message.requestId()));
                yield TransferOutcome.ok("a connection attempt for this player is already in progress");
            }
            case CONNECTION_CANCELLED -> TransferOutcome.failed(TransferError.REJECTED,
                    "connection cancelled by a proxy policy (ServerPreConnectEvent denied)");
            case SERVER_DISCONNECTED -> TransferOutcome.failed(TransferError.UNAVAILABLE,
                    "backend disconnected the player during connection setup");
            // forward-compatibility: an unknown/new enum constant from a future velocity version
            default -> TransferOutcome.failed(TransferError.UNKNOWN,
                    "unexpected connection result status: " + result.getStatus());
        };
    }

    /** Sends a dimension_transfer_result for {@code requestId} on the requesting connection. */
    private void sendResult(ServerConnection source, UUID requestId, TransferOutcome outcome) {
        DimensionTransferResultMessage result = new DimensionTransferResultMessage(
                outcome.ok(), requestId, null, outcome.error(), outcome.message());
        final byte[] bytes;
        try {
            bytes = ProtocolCodec.encodeToBytes(result);
        } catch (ProtocolEncodeException e) {
            // cannot happen for this message shape; log and give up rather than crash the listener
            logger.error("failed to encode dimension_transfer_result for {}: {}", shortId(requestId), e.getMessage());
            operations.stats().resultSendFailures.incrementAndGet();
            return;
        }
        boolean sent = platform.sendPluginMessage(source, channel, bytes);
        if (!sent) {
            // the source connection may already be gone (a successful switch tears it down);
            // piggyback on another player connected to the source backend. Correlation is
            // by request_id, so a different carrier connection is fine - the requesting
            // backend deduplicates on the id either way.
            try {
                sent = source.getServer().sendPluginMessage(channel, bytes);
                if (sent) {
                    operations.stats().resultSendFallbacks.incrementAndGet();
                    logger.debug("result for transfer {} delivered via piggyback on the source backend",
                            shortId(requestId));
                }
            } catch (RuntimeException e) {
                logger.warn("piggyback delivery of result for transfer {} failed: {}", shortId(requestId), e.toString());
            }
        }
        if (!sent) {
            operations.stats().resultSendFailures.incrementAndGet();
            logger.warn("could not deliver result for transfer {} to backend '{}'; the backend will reconcile on its own timeout",
                    shortId(requestId), source.getServerInfo().getName());
        }
    }

    private boolean isRoutingHealthy() {
        try {
            return routingHealthy.getAsBoolean();
        } catch (RuntimeException e) {
            logger.error("routing-health probe failed: {}", e.toString());
            return false; // fail closed
        }
    }

    /** The sanitized cause of the degraded state, or null when none could be determined. Never throws. */
    private String degradedReason() {
        if (degradedReasonSupplier == null) {
            return null;
        }
        try {
            String sanitized = DegradedReasons.sanitize(degradedReasonSupplier.get());
            return sanitized.isEmpty() ? null : sanitized;
        } catch (RuntimeException e) {
            logger.error("degraded-reason probe failed: {}", e.toString());
            return null;
        }
    }

    private Duration transferTimeout() {
        try {
            Duration timeout = timeoutSupplier.get();
            return timeout != null && !timeout.isNegative() && !timeout.isZero() ? timeout : Duration.ofSeconds(10);
        } catch (RuntimeException e) {
            logger.warn("unable to read transfer timeout from configuration ({}); using 10s", e.toString());
            return Duration.ofSeconds(10);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String shortId(UUID requestId) {
        return requestId.toString().substring(0, 8);
    }
}

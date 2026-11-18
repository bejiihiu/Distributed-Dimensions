package kz.bejiihiu.dimensions.velocity.transfer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic counters for the transfer path. All state lives in memory; the proxy keeps no
 * durable records by design (backends own the database), so counters reset on restart.
 * This is deliberate: the proxy is a router, not a ledger.
 */
public final class TransferStats {

    /** dimension_transfer_request messages accepted from a backend connection (pre-validation). */
    public final AtomicLong requestsReceived = new AtomicLong();
    /** Messages dropped because the sender was a player, not a backend connection. */
    public final AtomicLong droppedPlayerSourced = new AtomicLong();
    /** Messages dropped because the sender was neither a player nor a backend connection. */
    public final AtomicLong droppedUnknownSource = new AtomicLong();
    /** Backend messages torn down by the codec (bad JSON, oversize, bad fields). */
    public final AtomicLong decodeFailures = new AtomicLong();
    /** Messages dropped specifically because of an unsupported protocol version. */
    public final AtomicLong unsupportedVersion = new AtomicLong();
    /** Failure results sent back after rejecting an undecodable/unsupported message. */
    public final AtomicLong errorRepliesSent = new AtomicLong();
    /** Requests refused before validation because the proxy configuration is broken. */
    public final AtomicLong refusedBrokenConfig = new AtomicLong();
    /** Requests rejected by the validation pipeline (wrong source, unknown dimension, ...). */
    public final AtomicLong validationFailures = new AtomicLong();
    /** Re-delivered request_ids while the operation was still in flight (swallowed, no re-connect). */
    public final AtomicLong inFlightReplays = new AtomicLong();
    /** Re-delivered request_ids answered from the completed cache (no re-connect). */
    public final AtomicLong completedReplays = new AtomicLong();
    /** Re-delivered request_ids that belonged to a different player/server pair (rejected). */
    public final AtomicLong conflictingReplays = new AtomicLong();
    /** Connections that reached the target backend. */
    public final AtomicLong connectsSucceeded = new AtomicLong();
    /** Connections refused/dropped by the target backend or cancelled by policy. */
    public final AtomicLong connectsFailed = new AtomicLong();
    /** Connections that exceeded the configured transfer timeout. */
    public final AtomicLong connectsTimedOut = new AtomicLong();
    /** Operations marked abandoned because the player disconnected mid-connect. */
    public final AtomicLong abandonedOperations = new AtomicLong();
    /** Result delivery needed the piggyback path (the direct connection was already gone). */
    public final AtomicLong resultSendFallbacks = new AtomicLong();
    /** Result plugin messages the backend could not / did not accept. */
    public final AtomicLong resultSendFailures = new AtomicLong();
    /** Messages addressed to this proxy that the proxy does not own (results, respawn queries, ...). */
    public final AtomicLong unsolicitedMessages = new AtomicLong();
    /** Entity transfer requests refused with an explicit protocol failure. */
    public final AtomicLong entityTransferRefusals = new AtomicLong();

    /** Snapshot for status output; stable iteration order. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("requests-received", requestsReceived.get());
        out.put("dropped-player-sourced", droppedPlayerSourced.get());
        out.put("dropped-unknown-source", droppedUnknownSource.get());
        out.put("decode-failures", decodeFailures.get());
        out.put("unsupported-version", unsupportedVersion.get());
        out.put("error-replies-sent", errorRepliesSent.get());
        out.put("refused-broken-config", refusedBrokenConfig.get());
        out.put("validation-failures", validationFailures.get());
        out.put("in-flight-replays", inFlightReplays.get());
        out.put("completed-replays", completedReplays.get());
        out.put("conflicting-replays", conflictingReplays.get());
        out.put("connects-succeeded", connectsSucceeded.get());
        out.put("connects-failed", connectsFailed.get());
        out.put("connects-timed-out", connectsTimedOut.get());
        out.put("abandoned-operations", abandonedOperations.get());
        out.put("result-send-fallbacks", resultSendFallbacks.get());
        out.put("result-send-failures", resultSendFailures.get());
        out.put("unsolicited-messages", unsolicitedMessages.get());
        out.put("entity-transfer-refusals", entityTransferRefusals.get());
        return out;
    }
}

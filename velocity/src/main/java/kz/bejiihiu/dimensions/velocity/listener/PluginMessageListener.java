package kz.bejiihiu.dimensions.velocity.listener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.*;
import kz.bejiihiu.dimensions.velocity.transfer.TransferCoordinator;
import kz.bejiihiu.dimensions.velocity.transfer.TransferPlatform;
import kz.bejiihiu.dimensions.velocity.transfer.TransferStats;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Listener for the {@code distributeddimensions:transfer} channel.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>the channel is ours - anything else is ignored untouched;</li>
 *   <li>{@code setResult(ForwardResult.handled())} is set on <em>every</em> message on our
 *       channel - the default is {@code forward()}, which would leak messages to players or
 *       backends as if the proxy were transparent;</li>
 *   <li>only a {@link ServerConnection} source is considered a backend. Player-sourced (and
 *       other unknown-source) messages are dropped silently and counted;</li>
 *   <li>decoding is strict (codec enforces the size cap and the {@code v = 1} envelope);</li>
 *   <li>dispatch by message type. The proxy owns connection routing only:
 *     <ul>
 *       <li>{@code dimension_transfer_request} is validated and executed by the coordinator;</li>
 *       <li>{@code dimension_transfer_result}, {@code player_respawn_point_answer},
 *           {@code respawn_anchor_hint}, {@code entity_transfer_ack} - logged and dropped
 *           (the proxy has no business acting on them);</li>
 *       <li>{@code player_respawn_point_query} - logged and dropped; the proxy intentionally
 *           does <em>not</em> answer respawn queries (backends own bed/anchor state via the
 *           shared database; a fabricated empty answer would be indistinguishable from
 *           "no respawn point" and would corrupt the end-exit flow);</li>
 *       <li>{@code entity_transfer} - refused with an explicit
 *           {@code entity_transfer_ack{outcome=failed, error=rejected}} so the source backend
 *           gets a definitive answer instead of waiting out its timeout.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class PluginMessageListener {

    private final ChannelIdentifier channel;
    private final TransferCoordinator coordinator;
    private final TransferPlatform platform;
    private final TransferStats stats;
    private final Logger logger;

    public PluginMessageListener(ChannelIdentifier channel, TransferCoordinator coordinator,
                                 TransferPlatform platform, TransferStats stats, Logger logger) {
        this.channel = channel;
        this.coordinator = coordinator;
        this.platform = platform;
        this.stats = stats;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) {
            return; // not our channel; leave the default forwarding untouched
        }
        // our channel: never leak the message onwards, regardless of what we do with it
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        ChannelMessageSource source = event.getSource();
        if (!(source instanceof ServerConnection connection)) {
            if (source instanceof Player) {
                stats.droppedPlayerSourced.incrementAndGet();
                logger.debug("dropping player-sourced message on {} (possible spoof)", channel.getId());
            } else {
                stats.droppedUnknownSource.incrementAndGet();
                logger.debug("dropping message on {} from unrecognized source {}", channel.getId(), source.getClass().getName());
            }
            return;
        }

        final ProtocolMessage message;
        try {
            message = ProtocolCodec.decode(event.getData());
        } catch (ProtocolDecodeException e) {
            handleDecodeFailure(e, event.getData(), connection);
            return;
        } catch (RuntimeException e) {
            // a buggy codec must never escape into the event bus - treat any runtime
            // failure on untrusted bytes as a decode failure and drop the message
            stats.decodeFailures.incrementAndGet();
            logger.warn("dropping message on {} that crashed the decoder ({}): {}", channel.getId(), e.getClass().getSimpleName(), e.getMessage());
            return;
        }

        switch (message) {
            case DimensionTransferRequestMessage request -> coordinator.handleTransferRequest(request, connection);
            case DimensionTransferResultMessage result -> {
                stats.unsolicitedMessages.incrementAndGet();
                logger.warn("ignoring dimension_transfer_result at the proxy (request_id={}): "
                        + "results are correlated by the requesting backend, the proxy does not act on them", result.requestId());
            }
            case PlayerRespawnPointQueryMessage query -> {
                stats.unsolicitedMessages.incrementAndGet();
                logger.warn("ignoring player_respawn_point_query at the proxy (query_id={}): "
                        + "the proxy does not answer respawn queries - backends read the shared database", query.queryId());
            }
            case PlayerRespawnPointAnswerMessage answer -> {
                stats.unsolicitedMessages.incrementAndGet();
                logger.warn("ignoring unsolicited player_respawn_point_answer at the proxy (query_id={})", answer.queryId());
            }
            case RespawnAnchorHintMessage hint -> {
                stats.unsolicitedMessages.incrementAndGet();
                logger.warn("ignoring unsolicited respawn_anchor_hint at the proxy (player_uuid={})", hint.playerUuid());
            }
            case EntityTransferMessage transfer -> {
                stats.entityTransferRefusals.incrementAndGet();
                logger.warn("refusing entity_transfer (operation_id={}) from server '{}': "
                        + "the proxy does not route entity transfers yet", transfer.operationId(), transfer.sourceServer());
                sendEntityTransferRefusal(connection, transfer);
            }
            case EntityTransferAckMessage ack -> {
                stats.unsolicitedMessages.incrementAndGet();
                logger.warn("ignoring unsolicited entity_transfer_ack at the proxy (operation_id={}, entity_uuid={})",
                        ack.operationId(), ack.entityUuid());
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private void handleDecodeFailure(ProtocolDecodeException failure, byte[] rawPayload, ServerConnection source) {
        if (failure.error() == DecodeError.UNSUPPORTED_VERSION) {
            stats.unsupportedVersion.incrementAndGet();
            logger.warn("dropping message with unsupported protocol version on {}: {}", channel.getId(), failure.getMessage());
            UUID id = tryExtractCorrelationId(rawPayload);
            if (id != null) {
                coordinator.sendProtocolError(source, id, TransferError.REJECTED,
                        "unsupported protocol version (this build speaks " + ProtocolVersion.CURRENT + ")");
            }
            return;
        }
        stats.decodeFailures.incrementAndGet();
        logger.warn("dropping undecodable message on {} ({}): {}", channel.getId(), failure.error(), failure.getMessage());
    }

    private void sendEntityTransferRefusal(ServerConnection source, EntityTransferMessage transfer) {
        EntityTransferAckMessage refusal = new EntityTransferAckMessage(
                transfer.operationId(), transfer.root().entityUuid(), EntityTransferOutcome.FAILED,
                TransferError.REJECTED, "entity transfers are not enabled on this proxy");
        try {
            boolean sent = platform.sendPluginMessage(source, channel, ProtocolCodec.encodeToBytes(refusal));
            if (sent) {
                stats.errorRepliesSent.incrementAndGet();
            } else {
                stats.resultSendFailures.incrementAndGet();
                logger.warn("could not deliver entity_transfer refusal for operation {}", transfer.operationId());
            }
        } catch (ProtocolEncodeException | RuntimeException e) {
            stats.resultSendFailures.incrementAndGet();
            logger.error("failed to send entity_transfer refusal for operation {}: {}", transfer.operationId(), e.toString());
        }
    }

    /**
     * Best-effort extraction of a correlation id from a payload that failed to decode -
     * only used to answer unsupported-version messages. Looks at the envelope fields only
     * and never trusts the values; returns null when anything smells off.
     */
    private static UUID tryExtractCorrelationId(byte[] rawPayload) {
        try {
            JsonObject root = JsonParser.parseString(new String(rawPayload, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : new String[]{"request_id", "query_id", "operation_id"}) {
                var element = root.get(key);
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    try {
                        return UUID.fromString(element.getAsString());
                    } catch (IllegalArgumentException notUuid) {
                        // keep looking; this field is untrustworthy
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

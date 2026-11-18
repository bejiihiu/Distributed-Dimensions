package kz.bejiihiu.dimensions.velocity.listener;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.velocity.transfer.TransferCoordinator;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;
import kz.bejiihiu.dimensions.velocity.transfer.VelocityTransferPlatform;
import kz.bejiihiu.dimensions.velocity.support.AdjustableClock;
import kz.bejiihiu.dimensions.velocity.support.FakeServerConnection;
import kz.bejiihiu.dimensions.velocity.support.FakeTransferPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Channel security and dispatch: only backends may speak, every message on our channel is
 * consumed, and the proxy never answers for semantics it doesn't own.
 *
 * <p>A Player-sourced message follows the exact same guard clause as the unknown-source case
 * (both are "not a ServerConnection"); constructing a full Player fixture here would add no
 * signal because the pipeline never touches the player object before that check.
 */
class PluginMessageListenerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("test");
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("distributeddimensions", "transfer");
    private static final DimensionsSettings DIMENSIONS = new DimensionsSettings(
            new DimensionTarget("overworld", "minecraft:overworld"),
            new DimensionTarget("nether", "minecraft:the_nether"),
            new DimensionTarget("end", "minecraft:the_end"));

    private FakeServerConnection source;
    private FakeTransferPlatform platform;
    private TransferOperations operations;
    private PluginMessageListener listener;

    @BeforeEach
    void setUp() {
        source = new FakeServerConnection("overworld", 25_651);
        platform = new FakeTransferPlatform();
        operations = new TransferOperations(new AdjustableClock(Instant.parse("2026-11-18T12:00:00Z")));
        TransferCoordinator coordinator = new TransferCoordinator(platform, operations,
                () -> true, () -> DIMENSIONS, () -> Duration.ofSeconds(5), LOGGER, CHANNEL);
        listener = new PluginMessageListener(CHANNEL, coordinator, platform, operations.stats(), LOGGER);
    }

    private PluginMessageEvent event(ChannelMessageSource eventSource, MinecraftChannelIdentifier channel, byte[] data) {
        return new PluginMessageEvent(eventSource, source, channel, data);
    }

    private String entityTransferJson(UUID operationId, UUID entityUuid) {
        return """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":{"entity_uuid":"%s","entity_type":"minecraft:pig",
                         "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                         "velocity":{"x":0.0,"y":0.0,"z":0.0},
                         "passengers":[]}}
                """.formatted(operationId, entityUuid);
    }

    // --------------------------------------------------------------- channel rules

    @Test
    void messagesOnForeignChannelsAreIgnoredCompletely() {
        MinecraftChannelIdentifier foreign = MinecraftChannelIdentifier.create("other", "chan");
        PluginMessageEvent event = event(source, foreign, new byte[]{1, 2, 3});

        listener.onPluginMessage(event);

        assertTrue(event.getResult().isAllowed(), "foreign channels must keep the default forward() result");
        assertEquals(0, operations.stats().requestsReceived.get());
    }

    @Test
    void messagesFromNonBackendsAreDroppedAndCounted() {
        ChannelMessageSource unknown = new ChannelMessageSource() {
        };
        PluginMessageEvent event = event(unknown, CHANNEL, new byte[]{1, 2, 3});

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed(), "our channel is always handled()");
        assertEquals(1, operations.stats().droppedUnknownSource.get());
        assertEquals(0, platform.sent.size());
    }

    @Test
    void everyMessageOnOurChannelIsHandled() {
        MinecraftChannelIdentifier our = MinecraftChannelIdentifier.create("distributeddimensions", "transfer");
        PluginMessageEvent event = event(source, our, "garbage".getBytes());

        listener.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
    }

    // --------------------------------------------------------------- decode failures

    @Test
    void undecodablePayloadIsDroppedAndCounted() {
        PluginMessageEvent event = event(source, CHANNEL, "not json at all".getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().decodeFailures.get());
        assertEquals(0, platform.sent.size());
    }

    @Test
    void unsupportedVersionGetsACorrelatedRejection() {
        UUID requestId = UUID.randomUUID();
        String payload = """
                {"v":2,"type":"dimension_transfer_request","request_id":"%s"}
                """.formatted(requestId);
        PluginMessageEvent event = event(source, CHANNEL, payload.getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().unsupportedVersion.get());
        assertEquals(1, platform.sent.size(), "unsupported-version failure must be answered");
        DimensionTransferResultMessage reply = (DimensionTransferResultMessage) decodeLastSent();
        assertEquals(requestId, reply.requestId());
        assertEquals(TransferError.REJECTED, reply.error());
        assertTrue(reply.message().contains("unsupported protocol version"));
    }

    @Test
    void unsupportedVersionWithoutCorrelationIdIsOnlyDropped() {
        String payload = "{\"v\":2,\"type\":\"dimension_transfer_request\"}";
        PluginMessageEvent event = event(source, CHANNEL, payload.getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().unsupportedVersion.get());
        assertEquals(0, platform.sent.size());
    }

    // --------------------------------------------------------------- message dispatch

    @Test
    void entityTransferIsRefusedWithAnExplicitAck() {
        UUID operationId = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        PluginMessageEvent event = event(source, CHANNEL, entityTransferJson(operationId, entityUuid).getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().entityTransferRefusals.get());
        kz.bejiihiu.dimensions.common.protocol.ProtocolMessage sent = decodeLastSent();
        var ack = assertInstanceOf(kz.bejiihiu.dimensions.common.protocol.EntityTransferAckMessage.class, sent);
        assertEquals(operationId, ack.operationId());
        assertEquals(entityUuid, ack.entityUuid());
        assertEquals(kz.bejiihiu.dimensions.common.protocol.EntityTransferOutcome.FAILED, ack.outcome());
        assertEquals(TransferError.REJECTED, ack.error());
    }

    @Test
    void respawnPointQueriesAreIgnoredWithoutAnAnswer() {
        String query = """
                {"v":1,"type":"player_respawn_point_query","query_id":"%s","player_uuid":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        PluginMessageEvent event = event(source, CHANNEL, query.getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().unsolicitedMessages.get());
        assertEquals(0, platform.sent.size(), "the proxy must NOT answer respawn queries");
    }

    @Test
    void resultsArrivingAtTheProxyAreIgnored() {
        String result = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":true}
                """.formatted(UUID.randomUUID());
        PluginMessageEvent event = event(source, CHANNEL, result.getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().unsolicitedMessages.get());
        assertEquals(0, platform.sent.size());
    }

    @Test
    void anchorHintsArrivingAtTheProxyAreIgnored() {
        String hint = """
                {"v":1,"type":"respawn_anchor_hint","player_uuid":"%s","anchor_active":false}
                """.formatted(UUID.randomUUID());
        PluginMessageEvent event = event(source, CHANNEL, hint.getBytes());

        listener.onPluginMessage(event);

        assertEquals(1, operations.stats().unsolicitedMessages.get());
        assertEquals(0, platform.sent.size());
    }

    @Test
    void hostilePayloadThatCrashesTheDecoderIsDroppedSafely() {
        // anchor_active=true without anchor_dimension violates a record invariant;
        // the listener must survive it without leaking an exception into the event bus
        String hint = """
                {"v":1,"type":"respawn_anchor_hint","player_uuid":"%s","anchor_active":true}
                """.formatted(UUID.randomUUID());
        PluginMessageEvent event = event(source, CHANNEL, hint.getBytes());

        assertDoesNotThrow(() -> listener.onPluginMessage(event));
        assertEquals(1, operations.stats().decodeFailures.get());
        assertEquals(0, platform.sent.size());
    }

    // --------------------------------------------------------------- helpers

    private kz.bejiihiu.dimensions.common.protocol.ProtocolMessage decodeLastSent() {
        try {
            return ProtocolCodec.decode(platform.sent.get(platform.sent.size() - 1).bytes());
        } catch (kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException e) {
            throw new IllegalStateException(e);
        }
    }
}

package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.EntityTransferSpec;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnPoint;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferRequest;
import kz.bejiihiu.dimensions.common.model.Velocity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Record-level invariants that don't need the codec to observe. */
class MessageInvariantsTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    @Test
    void transferRequestMessageMirrorsDomainValidation() {
        TransferRequest valid = new TransferRequest(ID_A, ID_B, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.END_PORTAL);
        var message = DimensionTransferRequestMessage.fromTransferRequest(valid);
        assertEquals(valid, message.toTransferRequest());
        assertEquals(ID_A, message.requestId());
        assertEquals("dimension_transfer_request", message.type());

        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferRequestMessage(
                ID_A, ID_B, "overworld", "OVERWORLD",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.END_PORTAL));
    }

    @Test
    void transferResultMessageRejectsImpossibleShapes() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferResultMessage(
                true, ID_A, null, TransferError.TIMEOUT, null));
        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferResultMessage(
                false, ID_A, null, null, null));
        assertThrows(NullPointerException.class, () -> new DimensionTransferResultMessage(
                true, null, null, null, null));
    }

    @Test
    void entityAckRequiresErrorOnlyForFailure() {
        assertThrows(NullPointerException.class, () ->
                new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.FAILED, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.CREATED, TransferError.TIMEOUT, null));
        assertThrows(IllegalArgumentException.class, () ->
                new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.ALREADY_EXISTS, TransferError.REJECTED, null));
        new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.FAILED, TransferError.UNAVAILABLE, "overloaded");
        new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.ALREADY_EXISTS, null, null);
    }

    @Test
    void respawnAnchorHintCouplesFlagAndDimension() {
        assertThrows(NullPointerException.class, () -> new RespawnAnchorHintMessage(ID_A, true, null));
        assertThrows(IllegalArgumentException.class, () ->
                new RespawnAnchorHintMessage(ID_A, false, DimensionId.NETHER));
        new RespawnAnchorHintMessage(ID_A, true, DimensionId.NETHER);
        new RespawnAnchorHintMessage(ID_A, false, null);
    }

    @Test
    void entityTransferMessageRejectsSameServerAndBadNames() {
        EntityTransferSpec root = new EntityTransferSpec(ID_A, "minecraft:pig",
                new Position(0, 64, 0), Velocity.ZERO, List.of(), null);
        assertThrows(IllegalArgumentException.class, () ->
                new EntityTransferMessage(ID_A, "overworld", "OVERWORLD", DimensionId.NETHER, root));
        assertThrows(IllegalArgumentException.class, () ->
                new EntityTransferMessage(ID_A, "bad server!", "nether", DimensionId.NETHER, root));
        assertThrows(IllegalArgumentException.class, () ->
                new EntityTransferMessage(ID_A, "overworld", "nether back", DimensionId.NETHER, root));
    }

    @Test
    void idempotentMessagesExposeTheirCorrelationId() {
        for (var message : List.of(
                new DimensionTransferResultMessage(true, ID_A, null, null, null),
                new PlayerRespawnPointQueryMessage(ID_A, ID_B),
                new PlayerRespawnPointAnswerMessage(ID_A, null),
                new EntityTransferMessage(ID_A, "overworld", "nether", DimensionId.NETHER,
                        new EntityTransferSpec(ID_B, "minecraft:pig",
                                new Position(0, 64, 0), Velocity.ZERO, List.of(), null)),
                new EntityTransferAckMessage(ID_A, ID_B, EntityTransferOutcome.CREATED, null, null))) {
            assertEquals(ID_A, ((IdempotentOperation) message).requestId());
        }
    }

    @Test
    void wireTypeTokensAreStable() {
        assertEquals("dimension_transfer_request", DimensionTransferRequestMessage.TYPE);
        assertEquals("dimension_transfer_result", DimensionTransferResultMessage.TYPE);
        assertEquals("player_respawn_point_query", PlayerRespawnPointQueryMessage.TYPE);
        assertEquals("player_respawn_point_answer", PlayerRespawnPointAnswerMessage.TYPE);
        assertEquals("respawn_anchor_hint", RespawnAnchorHintMessage.TYPE);
        assertEquals("entity_transfer", EntityTransferMessage.TYPE);
        assertEquals("entity_transfer_ack", EntityTransferAckMessage.TYPE);
        assertEquals("created", EntityTransferOutcome.CREATED.wireName());
        assertEquals("already_exists", EntityTransferOutcome.ALREADY_EXISTS.wireName());
        assertEquals("failed", EntityTransferOutcome.FAILED.wireName());
        assertEquals(ProtocolVersion.CURRENT, 1);
        assertEquals(ProtocolVersion.MIN_SUPPORTED, 1);
    }

    @Test
    void respawnPointMessagesMapToModel() {
        RespawnPoint point = new RespawnPoint(DimensionId.OVERWORLD, new Position(1, 70, 2, 90, 0), RespawnKind.BED);
        var answer = PlayerRespawnPointAnswerMessage.fromPlayerRespawnAnswer(
                kz.bejiihiu.dimensions.common.model.PlayerRespawnAnswer.known(ID_A, point));
        assertEquals(point, answer.toPlayerRespawnAnswer().respawnPoint());
        assertEquals(ID_A, answer.requestId());
        assertTrue(answer.toPlayerRespawnAnswer().isKnown());
    }
}

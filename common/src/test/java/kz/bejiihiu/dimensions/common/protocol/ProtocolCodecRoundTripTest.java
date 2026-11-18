package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.EntityPortalCooldownHint;
import kz.bejiihiu.dimensions.common.model.EntityPortalCooldownType;
import kz.bejiihiu.dimensions.common.model.EntityTransferSpec;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPoint;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferRequest;
import kz.bejiihiu.dimensions.common.model.TransferResult;
import kz.bejiihiu.dimensions.common.model.Velocity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and determinism guarantees for every message type on the wire.
 */
class ProtocolCodecRoundTripTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ID_C = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private static final Position POSITION = new Position(-120.5, 64.0, 80.25, 90.0, -10.5);

    @Test
    void dimensionTransferRequestRoundTrips() throws Exception {
        TransferRequest request = new TransferRequest(ID_A, ID_B, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER, POSITION, TransferCause.NETHER_PORTAL);
        DimensionTransferRequestMessage message = DimensionTransferRequestMessage.fromTransferRequest(request);

        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(message));
        assertEquals(message, decoded);
        assertEquals(request, ((DimensionTransferRequestMessage) decoded).toTransferRequest());
    }

    @Test
    void dimensionTransferResultRoundTripsSuccessAndFailure() throws Exception {
        for (TransferResult result : List.of(
                TransferResult.success(ID_A, POSITION),
                TransferResult.success(ID_A),
                TransferResult.failure(ID_A, TransferError.BACKEND_DOWN, "connection refused"),
                TransferResult.failure(ID_A, TransferError.TIMEOUT))) {
            var message = DimensionTransferResultMessage.fromTransferResult(result);
            ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(message));
            assertEquals(message, decoded);
            assertEquals(result, ((DimensionTransferResultMessage) decoded).toTransferResult());
        }
    }

    @Test
    void playerRespawnQueryRoundTrips() throws Exception {
        var message = new PlayerRespawnPointQueryMessage(ID_A, ID_B);
        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(message));
        assertEquals(message, decoded);
        assertEquals(ID_A, ((PlayerRespawnPointQueryMessage) decoded).requestId());
    }

    @Test
    void playerRespawnAnswerRoundTripsKnownAndUnknown() throws Exception {
        RespawnPoint point = new RespawnPoint(DimensionId.NETHER, POSITION, RespawnKind.RESPAWN_ANCHOR);
        var known = new PlayerRespawnPointAnswerMessage(ID_A, point);
        var unknown = new PlayerRespawnPointAnswerMessage(ID_A, null);

        assertEquals(known, ProtocolCodec.decode(ProtocolCodec.encode(known)));
        assertEquals(unknown, ProtocolCodec.decode(ProtocolCodec.encode(unknown)));
        assertEquals(point, ((PlayerRespawnPointAnswerMessage) ProtocolCodec.decode(ProtocolCodec.encode(known))).respawnPoint());
        assertEquals(null, ((PlayerRespawnPointAnswerMessage) ProtocolCodec.decode(ProtocolCodec.encode(unknown))).respawnPoint());
    }

    @Test
    void respawnAnchorHintRoundTrips() throws Exception {
        var active = new RespawnAnchorHintMessage(ID_A, true, DimensionId.NETHER);
        var cleared = new RespawnAnchorHintMessage(ID_A, false, null);

        assertEquals(active, ProtocolCodec.decode(ProtocolCodec.encode(active)));
        assertEquals(cleared, ProtocolCodec.decode(ProtocolCodec.encode(cleared)));
    }

    @Test
    void entityTransferRoundTripsDeepPassengerTree() throws Exception {
        EntityTransferSpec pig = new EntityTransferSpec(
                ID_C, "minecraft:pig", POSITION, new Velocity(0.1, 0, -0.2), List.of(),
                EntityPortalCooldownHint.fresh(EntityPortalCooldownType.COMMON));
        EntityTransferSpec boat = new EntityTransferSpec(
                ID_B, "minecraft:boat", POSITION, Velocity.ZERO, List.of(pig), null);
        EntityTransferSpec minecart = new EntityTransferSpec(
                ID_A, "minecraft:chest_minecart", POSITION, new Velocity(1.5, 0, 0), List.of(boat),
                EntityPortalCooldownHint.fresh(EntityPortalCooldownType.VEHICLE));

        var message = new EntityTransferMessage(ID_A, "overworld", "nether", DimensionId.NETHER, minecart);
        ProtocolMessage decoded = ProtocolCodec.decode(ProtocolCodec.encode(message));
        assertEquals(message, decoded);
        assertEquals(
                "minecraft:chest_minecart > minecraft:boat > minecraft:pig",
                describe(((EntityTransferMessage) decoded).root()));
    }

    private static String describe(EntityTransferSpec spec) {
        if (spec.passengers().isEmpty()) {
            return spec.entityTypeKey();
        }
        return spec.entityTypeKey() + " > " + describe(spec.passengers().getFirst());
    }

    @Test
    void entityTransferAckRoundTripsAllOutcomes() throws Exception {
        for (var outcome : EntityTransferOutcome.values()) {
            var ack = outcome == EntityTransferOutcome.FAILED
                    ? new EntityTransferAckMessage(ID_A, ID_B, outcome, TransferError.UNAVAILABLE, "overloaded")
                    : new EntityTransferAckMessage(ID_A, ID_B, outcome, null, null);
            assertEquals(ack, ProtocolCodec.decode(ProtocolCodec.encode(ack)));
        }
    }

    @Test
    void encodingIsDeterministic() throws Exception {
        TransferRequest request = new TransferRequest(ID_A, ID_B, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER, POSITION, TransferCause.NETHER_PORTAL);
        var message = DimensionTransferRequestMessage.fromTransferRequest(request);

        String first = ProtocolCodec.encode(message);
        String second = ProtocolCodec.encode(message);
        assertEquals(first, second);
        assertEquals(first, new String(ProtocolCodec.encodeToBytes(message), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void encodedShapeIsStableAcrossRuns() throws Exception {
        var message = new DimensionTransferRequestMessage(ID_A, ID_B, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER, new Position(1.5, 64.0, -2.25, 45.0, 0.0),
                TransferCause.END_PORTAL);
        String json = ProtocolCodec.encode(message);

        // keys in a fixed order: v, type, then payload in declaration order
        assertTrue(json.startsWith("{\"v\":1,\"type\":\"dimension_transfer_request\",\"request_id\":\"" + ID_A + "\""), json);
        assertTrue(json.indexOf("\"player_uuid\"") < json.indexOf("\"source_server\""));
        assertTrue(json.indexOf("\"cause\"") > json.indexOf("\"target_position\""));
        // no insignificant whitespace
        assertEquals(-1, json.indexOf(": "));
    }

    @Test
    void unknownFieldsAreIgnoredEverywhere() throws Exception {
        // unknown top-level field
        String topLevel = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":true,"from_the_future":{"nested":[1,2,3]}}"""
                .formatted(ID_A);
        ProtocolMessage decoded = ProtocolCodec.decode(topLevel);
        assertTrue(((DimensionTransferResultMessage) decoded).ok());

        // unknown field inside a nested object
        String nested = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0,"wibble":"ignored"},
                 "cause":"nether_portal","unknown_top":42}""".formatted(ID_A, ID_B);
        ProtocolMessage decodedNested = ProtocolCodec.decode(nested);
        assertEquals(POSITION.y(), 64.0, 0.0001);
        assertEquals(new Position(1.0, 64.0, 2.0, 0.0, 0.0),
                ((DimensionTransferRequestMessage) decodedNested).targetPosition());
    }

    @Test
    void snakeCaseTokensAreStable() {
        assertEquals("nether_portal", TransferCause.NETHER_PORTAL.wireName());
        assertEquals("end_portal", TransferCause.END_PORTAL.wireName());
        assertEquals("respawn", TransferCause.RESPAWN.wireName());
        assertEquals("programmatic", TransferCause.PROGRAMMATIC.wireName());
        assertEquals("mirage_teleport", TransferCause.MIRAGE_TELEPORT.wireName());
        assertEquals("backend_down", TransferError.BACKEND_DOWN.wireName());
        assertEquals("disconnected", TransferError.DISCONNECTED.wireName());
        assertEquals(ProtocolVersion.CURRENT, 1);
    }
}

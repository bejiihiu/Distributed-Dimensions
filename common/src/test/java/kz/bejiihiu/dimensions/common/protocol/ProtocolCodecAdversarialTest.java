package kz.bejiihiu.dimensions.common.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression net for the decode path: adversarial payloads must surface as
 * {@link ProtocolDecodeException} — never as a bare RuntimeException escaping to the caller.
 * Record-constructor invariants (e.g. {@link RespawnAnchorHintMessage} with
 * {@code anchorActive = true} and no dimension) throw {@code NullPointerException}
 * internally; the codec wraps every throwable from decoding into
 * {@link ProtocolDecodeException} with the original as cause.
 */
class ProtocolCodecAdversarialTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    /**
     * Payloads that used to (or could plausibly) leak an unchecked exception from the
     * decode path. Each one must either decode cleanly or fail with ProtocolDecodeException —
     * nothing else may escape.
     */
    private static List<String> adversarialPayloads() {
        return List.of(
                // the canonical leak: record invariant throws NullPointerException internally
                """
                {"v":1,"type":"respawn_anchor_hint","player_uuid":"%s","anchor_active":true}""".formatted(ID_A),
                // record invariant violation via the false branch: dimension present but must be absent
                """
                {"v":1,"type":"respawn_anchor_hint","player_uuid":"%s","anchor_active":false,"anchor_dimension":"minecraft:the_nether"}"""
                        .formatted(ID_A),
                // respawn_answer: position object missing entirely
                """
                {"v":1,"type":"player_respawn_point_answer","query_id":"%s",
                 "respawn_point":{"dimension":"minecraft:the_nether","kind":"bed"}}""".formatted(ID_A),
                // respawn_point kind is a non-string
                """
                {"v":1,"type":"player_respawn_point_answer","query_id":"%s",
                 "respawn_point":{"dimension":"minecraft:the_nether","kind":42,
                   "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0}}}""".formatted(ID_A),
                // entity_transfer: passengers array carrying a JSON null
                """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":{"entity_uuid":"%s","entity_type":"minecraft:boat",
                   "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                   "velocity":{"x":0.0,"y":0.0,"z":0.0},
                   "passengers":[null]}}""".formatted(ID_A, ID_B),
                // entity_transfer: root is an array, not an object
                """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":[{"entity_uuid":"%s"}]}""".formatted(ID_A, ID_B),
                // entity_transfer: position member is an object where a number belongs
                """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":{"entity_uuid":"%s","entity_type":"minecraft:pig",
                   "position":{"x":{"y":0.0},"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                   "velocity":{"x":0.0,"y":0.0,"z":0.0}}}""".formatted(ID_A, ID_B),
                // entity_transfer: cooldown ticks overflow to infinity
                """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":{"entity_uuid":"%s","entity_type":"minecraft:pig",
                   "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                   "velocity":{"x":0.0,"y":0.0,"z":0.0},
                   "cooldown":{"type":"portal","ticks_remaining":1e400}}}""".formatted(ID_A, ID_B),
                // transfer_request: NaN literal in a coordinate
                """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1e400,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                 "cause":"nether_portal"}""".formatted(ID_A, ID_B),
                // version field overflows the double-to-int path
                """
                {"v":1e30,"type":"dimension_transfer_result","request_id":"%s","ok":true}""".formatted(ID_A),
                // version is a boolean
                """
                {"v":true,"type":"dimension_transfer_result","request_id":"%s","ok":true}""".formatted(ID_A),
                // player_uuid is a number, not a string
                """
                {"v":1,"type":"dimension_transfer_result","request_id":12345,"ok":true}""",
                // cause token over 64 bits of text
                """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                 "cause":"nether_portal","extra":{"nested":[{"deep":[1,2,{"deeper":[true,null,"text"]}]}]}}"""
                        .formatted(ID_A, ID_B),
                // entity_type is a non-ASCII string with control characters
                """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":{"entity_uuid":"%s","entity_type":"   ",
                   "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                   "velocity":{"x":0.0,"y":0.0,"z":0.0}}}""".formatted(ID_A, ID_B));
    }

    @Test
    void adversarialPayloadsNeverEscapeAsUncheckedExceptions() {
        for (String payload : adversarialPayloads()) {
            try {
                ProtocolCodec.decode(payload);
                // decoding cleanly is also a legal outcome — the contract is only
                // "no exception other than ProtocolDecodeException may escape"
            } catch (ProtocolDecodeException expected) {
                // the contract
            } catch (Throwable t) {
                fail("payload leaked " + t.getClass().getName() + ": " + payload.replace('\n', ' ')
                        + " -> " + t.getMessage());
            }
        }
    }

    @Test
    void adversarialPayloadsNeverEscapeAsUncheckedExceptionsFromBytes() {
        for (String payload : adversarialPayloads()) {
            try {
                ProtocolCodec.decode(payload.getBytes(StandardCharsets.UTF_8));
            } catch (ProtocolDecodeException expected) {
                // the contract
            } catch (Throwable t) {
                fail("byte payload leaked " + t.getClass().getName() + ": " + payload.replace('\n', ' '));
            }
        }
    }

    @Test
    void anchorHintWithoutDimensionSurfacesAsDecodeFailure() {
        // RespawnAnchorHintMessage's compact constructor throws NullPointerException for
        // anchorActive=true without a dimension; the codec must wrap it, never let it out
        String json = """
                {"v":1,"type":"respawn_anchor_hint","player_uuid":"%s","anchor_active":true}""".formatted(ID_A);

        ProtocolDecodeException e;
        try {
            ProtocolCodec.decode(json);
            fail("anchor hint without a dimension must not decode");
            return;
        } catch (ProtocolDecodeException thrown) {
            e = thrown;
        }

        assertEquals(DecodeError.INVALID_VALUE, e.error());
        assertInstanceOf(NullPointerException.class, e.getCause(),
                "the original record-invariant failure must be preserved as the cause");
    }
}

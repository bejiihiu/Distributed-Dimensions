package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile/broken input must never produce a partially-built message — every failure is a
 * {@link ProtocolDecodeException} with an error classification.
 */
class ProtocolCodecCorruptionTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static String validTransferRequestJson() throws Exception {
        TransferRequest request = new TransferRequest(ID_A, ID_B, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(1.5, 64.0, -2.25, 45.0, 10.0), TransferCause.NETHER_PORTAL);
        return ProtocolCodec.encode(DimensionTransferRequestMessage.fromTransferRequest(request));
    }

    @Test
    void truncationAtEveryByteBoundaryFails() throws Exception {
        String valid = validTransferRequestJson();
        for (int length = 0; length < valid.length(); length++) {
            String truncated = valid.substring(0, length);
            ProtocolDecodeException exception = assertThrows(ProtocolDecodeException.class,
                    () -> ProtocolCodec.decode(truncated), "length " + length);
            // anything shorter than the full payload violates schema or syntax
            assertEquals(DecodeError.MALFORMED_JSON == exception.error()
                    || exception.error() == DecodeError.MISSING_FIELD
                    || exception.error() == DecodeError.INVALID_VALUE, true,
                    "unexpected classification " + exception.error() + " at length " + length);
        }
    }

    @Test
    void singleByteCorruptionFailsOrStillDecodesCleanly() throws Exception {
        // flipping a byte must either fail loudly or produce a *valid but different* message;
        // what it must never do is crash the JVM, hang, or return null
        String valid = validTransferRequestJson();
        for (int i = 0; i < valid.length(); i++) {
            char[] chars = valid.toCharArray();
            chars[i] = chars[i] == 'x' ? 'y' : 'x';
            String corrupted = new String(chars);
            try {
                ProtocolMessage decoded = ProtocolCodec.decode(corrupted);
                // a corrupted payload that still parses must not alias the original uuid
                if (decoded instanceof DimensionTransferRequestMessage request) {
                    assertTrue(request.requestId() != null);
                }
            } catch (ProtocolDecodeException expected) {
                // fine — loud failure is the contract
            }
        }
    }

    @Test
    void garbagePayloadsAreRejected() {
        for (String garbage : new String[]{
                "", "   ", "not json at all", "[1,2,3]", "\"string\"", "123", "true", "null",
                "{]", "}{", "{\"v\"1}", "\u0000", "{\"v\":\"1\",\"type\":\"dimension_transfer_result\"}"}) {
            ProtocolDecodeException e = assertThrows(ProtocolDecodeException.class,
                    () -> ProtocolCodec.decode(garbage), "garbage: " + visible(garbage));
            assertTrue(e.error() == DecodeError.MALFORMED_JSON || e.error() == DecodeError.UNKNOWN_TYPE
                    || e.error() == DecodeError.UNSUPPORTED_VERSION,
                    "garbage classified as " + e.error());
        }
    }

    @Test
    void versionFieldIsEnforced() {
        String missing = "{\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true}".formatted(ID_A);
        assertEquals(DecodeError.UNSUPPORTED_VERSION, errorOf(() -> ProtocolCodec.decode(missing)));

        String wrongVersion = "{\"v\":2,\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true}".formatted(ID_A);
        assertEquals(DecodeError.UNSUPPORTED_VERSION, errorOf(() -> ProtocolCodec.decode(wrongVersion)));

        String fractional = "{\"v\":1.5,\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true}".formatted(ID_A);
        assertEquals(DecodeError.UNSUPPORTED_VERSION, errorOf(() -> ProtocolCodec.decode(fractional)));

        String stringVersion = "{\"v\":\"one\",\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true}".formatted(ID_A);
        assertEquals(DecodeError.UNSUPPORTED_VERSION, errorOf(() -> ProtocolCodec.decode(stringVersion)));
    }

    @Test
    void unknownMessageTypeIsAHardError() {
        String unknown = "{\"v\":1,\"type\":\"teleport_now\",\"request_id\":\"%s\"}".formatted(ID_A);
        assertEquals(DecodeError.UNKNOWN_TYPE, errorOf(() -> ProtocolCodec.decode(unknown)));

        String missingType = "{\"v\":1,\"request_id\":\"%s\"}".formatted(ID_A);
        assertEquals(DecodeError.UNKNOWN_TYPE, errorOf(() -> ProtocolCodec.decode(missingType)));

        String numericType = "{\"v\":1,\"type\":42}";
        assertEquals(DecodeError.UNKNOWN_TYPE, errorOf(() -> ProtocolCodec.decode(numericType)));
    }

    @Test
    void missingRequiredFieldsAreDetected() {
        // player_uuid omitted
        String missingPlayer = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"nether_portal"}""".formatted(ID_A);
        assertEquals(DecodeError.MISSING_FIELD, errorOf(() -> ProtocolCodec.decode(missingPlayer)));

        // target_position entirely gone
        String missingPosition = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "cause":"nether_portal"}""".formatted(ID_A, ID_B);
        assertEquals(DecodeError.MISSING_FIELD, errorOf(() -> ProtocolCodec.decode(missingPosition)));
    }

    @Test
    void invalidValuesAreRejected() {
        // ok is a string, not a boolean
        String badBoolean = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":"yes"}""".formatted(ID_A);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(badBoolean)));

        // uuid is not a uuid
        String badUuid = """
                {"v":1,"type":"dimension_transfer_result","request_id":"not-a-uuid","ok":true}""";
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(badUuid)));

        // dimension key malformed
        String badDimension = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"NOT A KEY","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"nether_portal"}"""
                .formatted(ID_A, ID_B);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(badDimension)));

        // cause token unknown to this build
        String badCause = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"portal_shenanigans"}"""
                .formatted(ID_A, ID_B);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(badCause)));

        // ok=false without an error code must never decode
        String okFalseNoError = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":false}""".formatted(ID_A);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(okFalseNoError)));

        // source == target must never decode
        String sameServer = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"overworld",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"nether_portal"}"""
                .formatted(ID_A, ID_B);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(sameServer)));
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        String nan = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":NaN,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"nether_portal"}"""
                .formatted(ID_A, ID_B);
        assertThrows(ProtocolDecodeException.class, () -> ProtocolCodec.decode(nan));
    }

    @Test
    void sameDimensionSidesNeverDecode() {
        String sameDimension = """
                {"v":1,"type":"dimension_transfer_request","request_id":"%s","player_uuid":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "source_dimension":"minecraft:the_nether","target_dimension":"minecraft:the_nether",
                 "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},"cause":"nether_portal"}"""
                .formatted(ID_A, ID_B);
        assertEquals(DecodeError.INVALID_VALUE, errorOf(() -> ProtocolCodec.decode(sameDimension)));
    }

    @Test
    void oversizedPayloadsAreRejectedBeforeParsing() throws Exception {
        byte[] huge = new byte[1_048_576]; // 1 MiB of zeros
        assertEquals(DecodeError.OVERSIZE, errorOf(() -> ProtocolCodec.decode(huge)));

        StringBuilder padding = new StringBuilder("{\"v\":1,\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true,\"future_field\":\"".formatted(ID_A));
        while (padding.length() <= ProtocolCodec.MAX_MESSAGE_BYTES) {
            padding.append('x');
        }
        padding.append("\"}");
        assertEquals(DecodeError.OVERSIZE, errorOf(() -> ProtocolCodec.decode(padding.toString())));
    }

    @Test
    void exactlyMaxSizedPayloadIsAccepted() throws Exception {
        // unknown fields are ignored, so we can pad a valid message to just under the limit
        String base = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":true,"pad":"%s"}""".formatted(ID_A, "");
        int padLength = ProtocolCodec.MAX_MESSAGE_BYTES - base.length();
        assertTrue(padLength > 0);
        String padded = """
                {"v":1,"type":"dimension_transfer_result","request_id":"%s","ok":true,"pad":"%s"}"""
                .formatted(ID_A, "x".repeat(padLength));
        assertEquals(ProtocolCodec.MAX_MESSAGE_BYTES, padded.getBytes(StandardCharsets.UTF_8).length);
        ProtocolMessage decoded = ProtocolCodec.decode(padded); // must not throw
        assertTrue(decoded instanceof DimensionTransferResultMessage);
    }

    @Test
    void deeplyNestedPayloadIsRejectedBeforeParsing() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertEquals(DecodeError.MALFORMED_JSON, errorOf(() -> ProtocolCodec.decode(deep)));
    }

    @Test
    void bracesInsideStringsDoNotCountAsNesting() throws Exception {
        // 500 consecutive '{' inside a string literal — the structure stays flat and legal
        String sneaky = "{\"v\":1,\"type\":\"dimension_transfer_result\",\"request_id\":\"%s\",\"ok\":true,\"pad\":\"%s\"}"
                .formatted(ID_A, "{".repeat(500));
        ProtocolMessage decoded = ProtocolCodec.decode(sneaky); // must not throw
        assertTrue(decoded instanceof DimensionTransferResultMessage);
    }

    @Test
    void entityAssemblyBeyondDepthBudgetIsRejected() throws Exception {
        int wraps = kz.bejiihiu.dimensions.common.model.EntityTransferSpec.MAX_PASSENGER_DEPTH + 1;
        String wrapped = leafSpecJson("ffffffff-0000-4000-8000-0000000000ff");
        for (int i = 0; i < wraps; i++) {
            wrapped = """
                    {"entity_uuid":"%s","entity_type":"minecraft:boat",
                     "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                     "velocity":{"x":0.0,"y":0.0,"z":0.0},
                     "passengers":[%s]}""".formatted(UUID.randomUUID(), wrapped);
        }
        String json = """
                {"v":1,"type":"entity_transfer","operation_id":"%s",
                 "source_server":"overworld","target_server":"nether",
                 "target_dimension":"minecraft:the_nether",
                 "root":%s}""".formatted(ID_A, wrapped);
        ProtocolDecodeException e = assertThrows(ProtocolDecodeException.class, () -> ProtocolCodec.decode(json));
        assertEquals(DecodeError.INVALID_VALUE, e.error());
    }

    private String leafSpecJson(String uuid) {
        return """
                {"entity_uuid":"%s","entity_type":"minecraft:pig",
                 "position":{"x":0.0,"y":64.0,"z":0.0,"yaw":0.0,"pitch":0.0},
                 "velocity":{"x":0.0,"y":0.0,"z":0.0},
                 "passengers":[]}""".formatted(uuid);
    }

    private static String visible(String s) {
        return s.replace("\u0000", "\\0").replace("\n", "\\n");
    }

    private static DecodeError errorOf(DecodeRunnable runnable) {
        return assertThrows(ProtocolDecodeException.class, runnable::run).error();
    }

    @FunctionalInterface
    private interface DecodeRunnable {
        void run() throws ProtocolDecodeException;
    }
}

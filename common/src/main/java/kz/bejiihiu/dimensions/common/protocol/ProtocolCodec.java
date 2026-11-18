package kz.bejiihiu.dimensions.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.RespawnPoint;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Deterministic JSON codec for the backend ↔ proxy wire protocol.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Platform-independent bytes.</b> The same message encodes to the same bytes every
 *       time (stable key order, compact output, UTF-8, no HTML escaping).</li>
 *   <li><b>Strict decoding.</b> Missing required fields, wrong types, non-finite numbers,
 *       out-of-grammar enum tokens, and invalid invariants all fail with
 *       {@link ProtocolDecodeException}. Unknown <em>fields</em> are ignored everywhere
 *       (forward compatibility); an unknown <em>type</em> is a hard error.</li>
 *   <li><b>Bounded input.</b> Payloads larger than {@value #MAX_MESSAGE_BYTES} bytes are
 *       rejected before parsing; structured nesting deeper than the format can legally
 *       produce is rejected by a cheap pre-scan before the parser ever sees it.</li>
 *   <li><b>Versioning.</b> The envelope carries {@code "v"}; only {@code v = 1} is accepted.</li>
 * </ul>
 *
 * <p>Transport overhead budget: Minecraft plugin messages are limited to 32,766 bytes —
 * that is the ceiling here, not an arbitrary choice. Entity assemblies bigger than that
 * must be chunked by a higher layer (out of scope for this codec).
 */
public final class ProtocolCodec {

    /** Maximum payload size in bytes — the Minecraft plugin-message limit. */
    public static final int MAX_MESSAGE_BYTES = 32766;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<String, CheckedDecoder> DECODERS =
            Map.of(
                    DimensionTransferRequestMessage.TYPE, ProtocolCodec::decodeTransferRequest,
                    DimensionTransferResultMessage.TYPE, ProtocolCodec::decodeTransferResult,
                    PlayerRespawnPointQueryMessage.TYPE, ProtocolCodec::decodeRespawnQuery,
                    PlayerRespawnPointAnswerMessage.TYPE, ProtocolCodec::decodeRespawnAnswer,
                    RespawnAnchorHintMessage.TYPE, ProtocolCodec::decodeAnchorHint,
                    EntityTransferMessage.TYPE, ProtocolCodec::decodeEntityTransfer,
                    EntityTransferAckMessage.TYPE, ProtocolCodec::decodeEntityTransferAck);

    /** Decoder throwing checked decode failures. */
    @FunctionalInterface
    private interface CheckedDecoder {
        ProtocolMessage decode(JsonObject root) throws ProtocolDecodeException;
    }

    private ProtocolCodec() {
    }

    // ------------------------------------------------------------- encoding

    /** Encodes a message to compact JSON. Throws if the result exceeds the channel budget. */
    public static String encode(ProtocolMessage message) throws ProtocolEncodeException {
        if (message == null) {
            throw new ProtocolEncodeException("cannot encode null");
        }
        JsonObject out = switch (message) {
            case DimensionTransferRequestMessage m -> encodeTransferRequest(m);
            case DimensionTransferResultMessage m -> encodeTransferResult(m);
            case PlayerRespawnPointQueryMessage m -> encodeRespawnQuery(m);
            case PlayerRespawnPointAnswerMessage m -> encodeRespawnAnswer(m);
            case RespawnAnchorHintMessage m -> encodeAnchorHint(m);
            case EntityTransferMessage m -> encodeEntityTransfer(m);
            case EntityTransferAckMessage m -> encodeEntityTransferAck(m);
        };
        String json = GSON.toJson(out);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_MESSAGE_BYTES) {
            throw new ProtocolEncodeException(
                    "encoded message exceeds " + MAX_MESSAGE_BYTES + " bytes (got " + bytes + ")");
        }
        return json;
    }

    /** UTF-8 bytes of {@link #encode(ProtocolMessage)}. */
    public static byte[] encodeToBytes(ProtocolMessage message) throws ProtocolEncodeException {
        return encode(message).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- decoding

    /** Decodes a JSON string. See class javadoc for the acceptance rules. */
    public static ProtocolMessage decode(String json) throws ProtocolDecodeException {
        try {
            if (json == null || json.isBlank()) {
                throw new ProtocolDecodeException(DecodeError.MALFORMED_JSON, "payload is null or blank");
            }
            if (json.length() > MAX_MESSAGE_BYTES) {
                throw new ProtocolDecodeException(DecodeError.OVERSIZE,
                        "payload exceeds " + MAX_MESSAGE_BYTES + " bytes");
            }
            WireFormat.rejectIfTooDeep(json);
            final JsonObject root;
            try {
                var parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) {
                    throw new ProtocolDecodeException(DecodeError.MALFORMED_JSON, "payload must be a JSON object");
                }
                root = parsed.getAsJsonObject();
            } catch (ProtocolDecodeException e) {
                throw e;
            } catch (JsonSyntaxException | IllegalStateException | StackOverflowError e) {
                throw new ProtocolDecodeException(DecodeError.MALFORMED_JSON, "payload is not well-formed JSON", e);
            }

            requireVersion(root);
            String type = requireType(root);
            CheckedDecoder decoder = DECODERS.get(type);
            if (decoder == null) {
                throw new ProtocolDecodeException(DecodeError.UNKNOWN_TYPE, "unknown message type: '" + type + "'");
            }
            return decoder.decode(root);
        } catch (ProtocolDecodeException e) {
            throw e;
        } catch (Throwable t) {
            // the decoders above translate expected schema violations precisely; this net catches
            // everything they miss (NPEs from record invariants, unexpected library failures, ...)
            // so a hostile payload can never surface as a bare RuntimeException to the caller
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "unexpected failure while decoding payload (" + t.getClass().getSimpleName() + ")", t);
        }
    }

    /** Decodes raw bytes; enforces the channel size limit exactly. */
    public static ProtocolMessage decode(byte[] utf8Payload) throws ProtocolDecodeException {
        try {
            if (utf8Payload == null) {
                throw new ProtocolDecodeException(DecodeError.MALFORMED_JSON, "payload is null");
            }
            if (utf8Payload.length > MAX_MESSAGE_BYTES) {
                throw new ProtocolDecodeException(DecodeError.OVERSIZE,
                        "payload exceeds " + MAX_MESSAGE_BYTES + " bytes (got " + utf8Payload.length + ")");
            }
            return decode(new String(utf8Payload, StandardCharsets.UTF_8));
        } catch (ProtocolDecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "unexpected failure while decoding payload bytes (" + t.getClass().getSimpleName() + ")", t);
        }
    }

    // ------------------------------------------------------------ envelope

    private static void requireVersion(JsonObject root) throws ProtocolDecodeException {
        var element = root.get(WireFormat.KEY_VERSION);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolDecodeException(DecodeError.UNSUPPORTED_VERSION,
                    "missing or non-numeric 'v' field");
        }
        double v;
        try {
            v = element.getAsDouble();
        } catch (NumberFormatException e) {
            throw new ProtocolDecodeException(DecodeError.UNSUPPORTED_VERSION, "unreadable 'v' field", e);
        }
        if (v != Math.floor(v)) {
            throw new ProtocolDecodeException(DecodeError.UNSUPPORTED_VERSION, "'v' must be an integer, got " + v);
        }
        int version = (int) v;
        if (version != ProtocolVersion.CURRENT) {
            throw new ProtocolDecodeException(DecodeError.UNSUPPORTED_VERSION,
                    "unsupported protocol version: " + version + " (this build speaks " + ProtocolVersion.CURRENT + ")");
        }
    }

    private static String requireType(JsonObject root) throws ProtocolDecodeException {
        var element = root.get(WireFormat.KEY_TYPE);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolDecodeException(DecodeError.UNKNOWN_TYPE, "missing or non-string 'type' field");
        }
        return element.getAsString();
    }

    private static ProtocolDecodeException invalidValue(IllegalArgumentException e) {
        return new ProtocolDecodeException(DecodeError.INVALID_VALUE, e.getMessage(), e);
    }

    // ------------------------------------------------- encode: individual

    private static JsonObject encodeTransferRequest(DimensionTransferRequestMessage m) {
        JsonObject out = WireFormat.envelope(DimensionTransferRequestMessage.TYPE);
        out.addProperty("request_id", m.requestId().toString());
        out.addProperty("player_uuid", m.playerUuid().toString());
        out.addProperty("source_server", m.sourceServer());
        out.addProperty("target_server", m.targetServer());
        out.addProperty("source_dimension", m.sourceDimension().key());
        out.addProperty("target_dimension", m.targetDimension().key());
        out.add("target_position", WireFormat.positionToJson(m.targetPosition()));
        out.addProperty("cause", m.cause().wireName());
        return out;
    }

    private static JsonObject encodeTransferResult(DimensionTransferResultMessage m) {
        JsonObject out = WireFormat.envelope(DimensionTransferResultMessage.TYPE);
        out.addProperty("request_id", m.requestId().toString());
        out.addProperty("ok", m.ok());
        if (m.finalPosition() != null) {
            out.add("final_position", WireFormat.positionToJson(m.finalPosition()));
        }
        if (m.error() != null) {
            out.addProperty("error", m.error().wireName());
        }
        if (m.message() != null) {
            out.addProperty("message", m.message());
        }
        return out;
    }

    private static JsonObject encodeRespawnQuery(PlayerRespawnPointQueryMessage m) {
        JsonObject out = WireFormat.envelope(PlayerRespawnPointQueryMessage.TYPE);
        out.addProperty("query_id", m.queryId().toString());
        out.addProperty("player_uuid", m.playerUuid().toString());
        return out;
    }

    private static JsonObject encodeRespawnAnswer(PlayerRespawnPointAnswerMessage m) {
        JsonObject out = WireFormat.envelope(PlayerRespawnPointAnswerMessage.TYPE);
        out.addProperty("query_id", m.queryId().toString());
        if (m.respawnPoint() != null) {
            out.add("respawn_point", WireFormat.respawnPointToJson(m.respawnPoint()));
        }
        return out;
    }

    private static JsonObject encodeAnchorHint(RespawnAnchorHintMessage m) {
        JsonObject out = WireFormat.envelope(RespawnAnchorHintMessage.TYPE);
        out.addProperty("player_uuid", m.playerUuid().toString());
        out.addProperty("anchor_active", m.anchorActive());
        if (m.anchorDimension() != null) {
            out.addProperty("anchor_dimension", m.anchorDimension().key());
        }
        return out;
    }

    private static JsonObject encodeEntityTransfer(EntityTransferMessage m) {
        JsonObject out = WireFormat.envelope(EntityTransferMessage.TYPE);
        out.addProperty("operation_id", m.operationId().toString());
        out.addProperty("source_server", m.sourceServer());
        out.addProperty("target_server", m.targetServer());
        out.addProperty("target_dimension", m.targetDimension().key());
        out.add("root", WireFormat.specToJson(m.root()));
        return out;
    }

    private static JsonObject encodeEntityTransferAck(EntityTransferAckMessage m) {
        JsonObject out = WireFormat.envelope(EntityTransferAckMessage.TYPE);
        out.addProperty("operation_id", m.operationId().toString());
        out.addProperty("entity_uuid", m.entityUuid().toString());
        out.addProperty("outcome", m.outcome().wireName());
        if (m.error() != null) {
            out.addProperty("error", m.error().wireName());
        }
        if (m.message() != null) {
            out.addProperty("message", m.message());
        }
        return out;
    }

    // ------------------------------------------------- decode: individual

    private static ProtocolMessage decodeTransferRequest(JsonObject obj) throws ProtocolDecodeException {
        try {
            return new DimensionTransferRequestMessage(
                    WireFormat.requiredUuid(obj, "request_id"),
                    WireFormat.requiredUuid(obj, "player_uuid"),
                    WireFormat.requiredString(obj, "source_server"),
                    WireFormat.requiredString(obj, "target_server"),
                    WireFormat.dimension(obj, "source_dimension"),
                    WireFormat.dimension(obj, "target_dimension"),
                    WireFormat.positionFromJson(obj.get("target_position"), "target_position"),
                    WireFormat.cause(WireFormat.requiredString(obj, "cause")));
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
    }

    private static ProtocolMessage decodeTransferResult(JsonObject obj) throws ProtocolDecodeException {
        try {
            return new DimensionTransferResultMessage(
                    WireFormat.requiredBoolean(obj, "ok"),
                    WireFormat.requiredUuid(obj, "request_id"),
                    WireFormat.optionalPosition(obj, "final_position"),
                    WireFormat.optionalError(obj, "error"),
                    WireFormat.optionalString(obj, "message"));
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
    }

    private static ProtocolMessage decodeRespawnQuery(JsonObject obj) throws ProtocolDecodeException {
        return new PlayerRespawnPointQueryMessage(
                WireFormat.requiredUuid(obj, "query_id"),
                WireFormat.requiredUuid(obj, "player_uuid"));
    }

    private static ProtocolMessage decodeRespawnAnswer(JsonObject obj) throws ProtocolDecodeException {
        RespawnPoint point = WireFormat.optionalRespawnPoint(obj, "respawn_point");
        return new PlayerRespawnPointAnswerMessage(
                WireFormat.requiredUuid(obj, "query_id"), point);
    }

    private static ProtocolMessage decodeAnchorHint(JsonObject obj) throws ProtocolDecodeException {
        boolean anchorActive = WireFormat.requiredBoolean(obj, "anchor_active");
        DimensionId anchorDimension = WireFormat.optionalDimension(obj, "anchor_dimension");
        try {
            return new RespawnAnchorHintMessage(
                    WireFormat.requiredUuid(obj, "player_uuid"), anchorActive, anchorDimension);
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
    }

    private static ProtocolMessage decodeEntityTransfer(JsonObject obj) throws ProtocolDecodeException {
        try {
            return new EntityTransferMessage(
                    WireFormat.requiredUuid(obj, "operation_id"),
                    WireFormat.requiredString(obj, "source_server"),
                    WireFormat.requiredString(obj, "target_server"),
                    WireFormat.dimension(obj, "target_dimension"),
                    WireFormat.specFromJson(obj.get("root"), "root", 1));
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
    }

    private static ProtocolMessage decodeEntityTransferAck(JsonObject obj) throws ProtocolDecodeException {
        EntityTransferOutcome outcome;
        try {
            outcome = EntityTransferOutcome.fromWireName(WireFormat.requiredString(obj, "outcome"));
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
        try {
            return new EntityTransferAckMessage(
                    WireFormat.requiredUuid(obj, "operation_id"),
                    WireFormat.requiredUuid(obj, "entity_uuid"),
                    outcome,
                    WireFormat.optionalError(obj, "error"),
                    WireFormat.optionalString(obj, "message"));
        } catch (IllegalArgumentException e) {
            throw invalidValue(e);
        }
    }
}

package kz.bejiihiu.dimensions.common.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.EntityPortalCooldownHint;
import kz.bejiihiu.dimensions.common.model.EntityPortalCooldownType;
import kz.bejiihiu.dimensions.common.model.EntityTransferSpec;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPoint;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.Velocity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Package-private JSON plumbing for the wire codec. Every reader is strict: a field that is
 * absent, mistyped, out of range, or otherwise malformed throws
 * {@link ProtocolDecodeException} with a precise {@link DecodeError} — never a bare
 * NumberFormatException leaking to the caller.
 *
 * <p>Unknown keys are ignored everywhere (top level and nested objects); writers therefore
 * simply never emit keys for null values.
 */
final class WireFormat {

    static final String KEY_VERSION = "v";
    static final String KEY_TYPE = "type";

    // hard cap on structured nesting we'll scan before handing the payload to the parser;
    // generous next to the deepest legal payload (a max-depth passenger assembly)
    static final int MAX_JSON_DEPTH = 64;

    private WireFormat() {
    }

    // ------------------------------------------------------------------ envelope

    static JsonObject envelope(String type) {
        JsonObject out = new JsonObject();
        out.addProperty(KEY_VERSION, ProtocolVersion.CURRENT);
        out.addProperty(KEY_TYPE, type);
        return out;
    }

    /**
     * Linear pre-scan rejecting payloads nested deeper than {@link #MAX_JSON_DEPTH}. Guards the
     * recursive-descent parser from stack exhaustion on hostile input; counts only structural
     * braces/brackets, correctly skipping those inside string literals and escapes.
     */
    static void rejectIfTooDeep(String raw) throws ProtocolDecodeException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    if (++depth > MAX_JSON_DEPTH) {
                        throw new ProtocolDecodeException(
                                DecodeError.MALFORMED_JSON, "payload nesting exceeds " + MAX_JSON_DEPTH);
                    }
                }
                case '}', ']' -> depth--;
                default -> { /* irrelevant to depth */ }
            }
        }
    }

    // ------------------------------------------------------------- primitives

    static String requiredString(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + key + "'");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a string");
        }
        String value = element.getAsString().strip();
        if (value.isEmpty()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must not be blank");
        }
        return value;
    }

    static String optionalString(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a string");
        }
        return element.getAsString();
    }

    static boolean requiredBoolean(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + key + "'");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    static double requiredDouble(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + key + "'");
        }
        return numberToDouble(element, key);
    }

    /** Strict integral read — a fractional literal is rejected, not truncated. */
    static int requiredInt(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + key + "'");
        }
        double d = numberToDouble(element, key);
        if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be an integer, got " + d);
        }
        return (int) d;
    }

    static UUID requiredUuid(JsonObject obj, String key) throws ProtocolDecodeException {
        String raw = requiredString(obj, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + key + "' is not a valid UUID: '" + raw + "'", e);
        }
    }

    static DimensionId dimension(JsonObject obj, String key) throws ProtocolDecodeException {
        return dimensionFromString(requiredString(obj, key), key);
    }

    static DimensionId dimensionFromString(String value, String field) throws ProtocolDecodeException {
        try {
            return DimensionId.of(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + field + "' is not a valid dimension resource key: '" + value + "'", e);
        }
    }

    static TransferCause cause(String value) throws ProtocolDecodeException {
        try {
            return TransferCause.fromWireName(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "unknown transfer cause token: '" + value + "'", e);
        }
    }

    static TransferError error(String value) throws ProtocolDecodeException {
        try {
            return TransferError.fromWireName(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "unknown transfer error token: '" + value + "'", e);
        }
    }

    static RespawnKind respawnKind(String value) throws ProtocolDecodeException {
        try {
            return RespawnKind.fromWireName(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "unknown respawn kind token: '" + value + "'", e);
        }
    }

    static EntityPortalCooldownType cooldownType(String value) throws ProtocolDecodeException {
        try {
            return EntityPortalCooldownType.fromWireName(value);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "unknown cooldown type token: '" + value + "'", e);
        }
    }

    private static final java.util.regex.Pattern SERVER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Same server-name grammar as the domain layer; mirrors it exactly on purpose. */
    static boolean isWellFormedServerName(String name) {
        return name != null && SERVER_NAME.matcher(name).matches();
    }

    private static double numberToDouble(JsonElement element, String key) throws ProtocolDecodeException {
        if (!element.isJsonPrimitive()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a number");
        }
        double d;
        try {
            d = primitive.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + key + "' is not a readable number", e);
        }
        if (!Double.isFinite(d)) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be finite, got " + d);
        }
        return d;
    }

    // ------------------------------------------------------------ structured

    static JsonObject positionToJson(Position p) {
        JsonObject out = new JsonObject();
        out.addProperty("x", p.x());
        out.addProperty("y", p.y());
        out.addProperty("z", p.z());
        out.addProperty("yaw", p.yaw());
        out.addProperty("pitch", p.pitch());
        return out;
    }

    /** Null-tolerant position read — absent key means absent value, never a default. */
    static Position optionalPosition(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return positionFromJson(element, key);
    }

    /** Null-tolerant error-code read. */
    static TransferError optionalError(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a string");
        }
        return error(element.getAsString());
    }

    /** Null-tolerant dimension read. */
    static DimensionId optionalDimension(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + key + "' must be a string");
        }
        return dimensionFromString(element.getAsString(), key);
    }

    /** Null-tolerant respawn point read. */
    static RespawnPoint optionalRespawnPoint(JsonObject obj, String key) throws ProtocolDecodeException {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return respawnPointFromJson(element, key);
    }

    static Position positionFromJson(JsonElement element, String field) throws ProtocolDecodeException {
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + field + "'");
        }
        JsonObject obj = requireObject(element, field);
        double x = requiredDouble(obj, "x");
        double y = requiredDouble(obj, "y");
        double z = requiredDouble(obj, "z");
        double yaw = requiredDouble(obj, "yaw");
        double pitch = requiredDouble(obj, "pitch");
        try {
            return new Position(x, y, z, yaw, pitch);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + field + "': " + e.getMessage(), e);
        }
    }

    static JsonObject velocityToJson(Velocity v) {
        JsonObject out = new JsonObject();
        out.addProperty("x", v.x());
        out.addProperty("y", v.y());
        out.addProperty("z", v.z());
        return out;
    }

    static Velocity velocityFromJson(JsonElement element, String field) throws ProtocolDecodeException {
        JsonObject obj = requireObject(element, field);
        try {
            return new Velocity(requiredDouble(obj, "x"), requiredDouble(obj, "y"), requiredDouble(obj, "z"));
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + field + "': " + e.getMessage(), e);
        }
    }

    static JsonObject cooldownToJson(EntityPortalCooldownHint hint) {
        JsonObject out = new JsonObject();
        out.addProperty("type", hint.type().wireName());
        out.addProperty("ticks_remaining", hint.ticksRemaining());
        return out;
    }

    static EntityPortalCooldownHint cooldownFromJson(JsonElement element, String field) throws ProtocolDecodeException {
        JsonObject obj = requireObject(element, field);
        return new EntityPortalCooldownHint(
                cooldownType(requiredString(obj, "type")),
                requiredInt(obj, "ticks_remaining"));
    }

    static JsonObject respawnPointToJson(RespawnPoint point) {
        JsonObject out = new JsonObject();
        out.addProperty("dimension", point.dimension().key());
        out.add("position", positionToJson(point.position()));
        out.addProperty("kind", point.kind().wireName());
        return out;
    }

    static RespawnPoint respawnPointFromJson(JsonElement element, String field) throws ProtocolDecodeException {
        JsonObject obj = requireObject(element, field);
        return new RespawnPoint(
                dimension(obj, "dimension"),
                positionFromJson(obj.get("position"), field + ".position"),
                respawnKind(requiredString(obj, "kind")));
    }

    // ---------------------------------------------------- entity transfer

    static JsonObject specToJson(EntityTransferSpec spec) {
        JsonObject out = new JsonObject();
        out.addProperty("entity_uuid", spec.entityUuid().toString());
        out.addProperty("entity_type", spec.entityTypeKey());
        out.add("position", positionToJson(spec.position()));
        out.add("velocity", velocityToJson(spec.velocity()));
        com.google.gson.JsonArray passengers = new com.google.gson.JsonArray();
        for (EntityTransferSpec passenger : spec.passengers()) {
            passengers.add(specToJson(passenger));
        }
        out.add("passengers", passengers);
        if (spec.cooldownHint() != null) {
            out.add("cooldown", cooldownToJson(spec.cooldownHint()));
        }
        return out;
    }

    static EntityTransferSpec specFromJson(JsonElement element, String field, int depth) throws ProtocolDecodeException {
        if (depth > EntityTransferSpec.MAX_PASSENGER_DEPTH) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + field + "': passenger nesting exceeds " + EntityTransferSpec.MAX_PASSENGER_DEPTH);
        }
        JsonObject obj = requireObject(element, field);
        UUID entityUuid;
        try {
            entityUuid = UUID.fromString(requiredString(obj, "entity_uuid"));
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + field + ".entity_uuid' is not a valid UUID", e);
        }
        Position position = positionFromJson(obj.get("position"), field + ".position");
        Velocity velocity = velocityFromJson(obj.get("velocity"), field + ".velocity");
        List<EntityTransferSpec> passengers = new ArrayList<>();
        JsonElement passengersElement = obj.get("passengers");
        if (passengersElement != null && !passengersElement.isJsonNull()) {
            if (!passengersElement.isJsonArray()) {
                throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                        "field '" + field + ".passengers' must be an array");
            }
            for (JsonElement child : passengersElement.getAsJsonArray()) {
                passengers.add(specFromJson(child, field + ".passengers", depth + 1));
            }
        }
        EntityPortalCooldownHint cooldown = null;
        JsonElement cooldownElement = obj.get("cooldown");
        if (cooldownElement != null && !cooldownElement.isJsonNull()) {
            cooldown = cooldownFromJson(cooldownElement, field + ".cooldown");
        }
        try {
            return new EntityTransferSpec(
                    entityUuid,
                    requiredString(obj, "entity_type"),
                    position,
                    velocity,
                    passengers,
                    cooldown);
        } catch (IllegalArgumentException e) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE,
                    "field '" + field + "': " + e.getMessage(), e);
        }
    }

    static JsonObject requireObject(JsonElement element, String field) throws ProtocolDecodeException {
        if (element == null) {
            throw new ProtocolDecodeException(DecodeError.MISSING_FIELD, "missing required field '" + field + "'");
        }
        if (!element.isJsonObject()) {
            throw new ProtocolDecodeException(DecodeError.INVALID_VALUE, "field '" + field + "' must be an object");
        }
        return element.getAsJsonObject();
    }
}

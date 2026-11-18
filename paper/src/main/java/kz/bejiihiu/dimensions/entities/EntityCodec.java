package kz.bejiihiu.dimensions.entities;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The wire codec between entity graphs and payload rows. Nodes travel pre-order
 * (root first), one row per node; the row's {@code payload_json} carries exactly
 * {@code {kind, type, parent, depth, fields}} where {@code fields} is owned by the
 * node's serializer.
 *
 * <p>Byte arrays from the game (item NBT blobs) are encoded as <em>explicit Base64
 * strings</em> — never through Gson's default byte[] handling, which would materialise
 * them as arrays of boxed numbers.
 */
public final class EntityCodec {

    private static final Gson GSON = new Gson();
    private static final int MAX_ROWS = 64 * 16;     // 64 direct passengers @ depth 16
    private static final int MAX_DEPTH = 16;

    private EntityCodec() {
    }

    /** Serialize one node's {@code payload_json} body (without the row framing). */
    public static JsonObject nodeBody(EntityNode node) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", node.kind().wireName());
        body.addProperty("type", node.entityTypeKey());
        body.addProperty("parent", node.parentSeq());
        body.addProperty("depth", node.depth());
        body.add("fields", node.fields());
        return body;
    }

    /** Build the rows for an assembler: rows in pre-order, seq = row index. */
    public static List<EntityTransferPayload> toRows(List<EntityNode> graph, String opId,
            String targetServer, String targetDimension, UUID playerUuid, Instant now) {
        if (graph.isEmpty()) {
            throw new IllegalArgumentException("an assembly graph must contain at least the root node");
        }
        List<EntityTransferPayload> rows = new ArrayList<>(graph.size());
        for (int i = 0; i < graph.size(); i++) {
            EntityNode node = graph.get(i);
            rows.add(EntityTransferPayload.pending(opId, i, node.kind(),
                    targetServer, targetDimension, playerUuid, GSON.toJson(nodeBody(node)), now));
        }
        return List.copyOf(rows);
    }

    /**
     * Decode the rows of one operation back into the node graph, validating structure:
     * non-empty input, contiguous seq from 0, known kinds, parent references to
     * earlier rows, and a depth ceiling.
     *
     * @throws CodecException when the payload is structurally invalid
     */
    public static List<EntityNode> fromRows(List<EntityTransferPayload> rows) {
        if (rows.isEmpty()) {
            throw new CodecException("no rows to decode");
        }
        List<EntityTransferPayload> ordered = rows.stream()
                .sorted(java.util.Comparator.comparingInt(EntityTransferPayload::seq))
                .toList();
        if (ordered.size() > MAX_ROWS) {
            throw new CodecException("assembly exceeds " + MAX_ROWS + " rows (" + ordered.size() + ")");
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).seq() != i) {
                throw new CodecException("rows are not contiguous from 0: got seq " + ordered.get(i).seq() + " at index " + i);
            }
        }
        List<EntityNode> nodes = new ArrayList<>(ordered.size());
        for (EntityTransferPayload row : ordered) {
            nodes.add(parseNode(row));
        }
        for (int i = 0; i < nodes.size(); i++) {
            EntityNode node = nodes.get(i);
            if (node.depth() > MAX_DEPTH) {
                throw new CodecException("node exceeds max depth " + MAX_DEPTH);
            }
            int parent = node.parentSeq();
            if (parent != -1 && (parent >= i)) {
                throw new CodecException("node parent " + parent + " points forward or out of bounds at seq " + i);
            }
        }
        return List.copyOf(nodes);
    }

    private static EntityNode parseNode(EntityTransferPayload row) {
        final JsonObject body;
        try {
            JsonElement parsed = JsonParser.parseString(row.payloadJson());
            if (!parsed.isJsonObject()) {
                throw new CodecException("payload_json is not a JSON object (op " + row.opId() + " seq " + row.seq() + ")");
            }
            body = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new CodecException("payload_json is not valid JSON (op " + row.opId() + " seq " + row.seq() + "): " + e.getMessage(), e);
        }
        String kindToken = stringField(body, "kind", row);
        String typeKey = stringField(body, "type", row);
        int parent = intField(body, "parent", -1, row);
        int depth = intField(body, "depth", 0, row);
        JsonObject fields = body.getAsJsonObject("fields");
        if (fields == null) {
            throw new CodecException("payload_json missing object field 'fields' (op " + row.opId() + " seq " + row.seq() + ")");
        }
        final EntityPayloadKind kind;
        try {
            kind = EntityPayloadKind.fromWireName(kindToken);
        } catch (IllegalArgumentException e) {
            throw new CodecException("unknown kind token '" + kindToken + "' (op " + row.opId() + " seq " + row.seq() + ")", e);
        }
        // keep row.kind() authoritative — the two must agree
        if (kind != row.kind()) {
            throw new CodecException("payload kind token '" + kindToken + "' disagrees with row kind '" + row.kind().wireName() + "'");
        }
        try {
            return new EntityNode(kind, typeKey, parent, depth, fields);
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid node fields in op " + row.opId() + " seq " + row.seq() + ": " + e.getMessage(), e);
        }
    }

    private static String stringField(JsonObject body, String name, EntityTransferPayload row) {
        JsonElement element = body.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new CodecException("payload_json missing string field '" + name + "' (op " + row.opId() + " seq " + row.seq() + ")");
        }
        return element.getAsString();
    }

    private static int intField(JsonObject body, String name, int fallback, EntityTransferPayload row) {
        JsonElement element = body.get(name);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new CodecException("payload_json field '" + name + "' is not a number (op " + row.opId() + " seq " + row.seq() + ")");
        }
        return element.getAsInt();
    }

}

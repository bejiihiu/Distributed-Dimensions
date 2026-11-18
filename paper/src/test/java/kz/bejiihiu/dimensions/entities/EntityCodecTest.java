package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCodecTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final String OP = UUID.randomUUID().toString();

    private static EntityNode node(EntityPayloadKind kind, int parent, int depth) {
        JsonObject fields = new JsonObject();
        fields.addProperty("marker", "keep-me");
        return new EntityNode(kind, "minecraft:minecart", parent, depth, fields);
    }

    private static EntityTransferPayload row(String opId, int seq, EntityPayloadKind kind, String json) {
        return new EntityTransferPayload(opId, seq, kind, "nether", "minecraft:nether", null,
                json, EntityPayloadState.PENDING, T0, T0);
    }

    @Test
    void rowsRoundTripPreservesOrderAndFields() {
        JsonObject rootFields = new JsonObject();
        rootFields.addProperty("depthMarker", "root");
        List<EntityNode> graph = List.of(
                new EntityNode(EntityPayloadKind.MINECART, "minecraft:minecart", -1, 0, rootFields),
                new EntityNode(EntityPayloadKind.MOB, "minecraft:pig", 0, 1, new JsonObject()),
                new EntityNode(EntityPayloadKind.ITEM, "minecraft:item", 1, 2, new JsonObject()));
        List<EntityTransferPayload> rows = EntityCodec.toRows(graph, OP, "nether", "minecraft:nether", null, T0);
        assertEquals(List.of(0, 1, 2), rows.stream().map(EntityTransferPayload::seq).toList());

        List<EntityNode> back = EntityCodec.fromRows(rows);
        assertEquals(3, back.size());
        assertEquals(-1, back.get(0).parentSeq());
        assertEquals(0, back.get(1).parentSeq());
        assertEquals(1, back.get(2).parentSeq());
        assertEquals(0, back.get(0).depth());
        assertEquals("root", back.get(0).fields().get("depthMarker").getAsString());
        assertEquals("minecraft:pig", back.get(1).entityTypeKey());
    }

    @Test
    void fromRowsRejectsNonContiguousSeq() {
        EntityNode node = node(EntityPayloadKind.ITEM, -1, 0);
        String json = com.google.gson.JsonParser.parseString(
                "{\"kind\":\"item\",\"type\":\"minecraft:item\",\"parent\":-1,\"depth\":0,\"fields\":{}}")
                .getAsJsonObject().toString();
        List<EntityTransferPayload> rows = List.of(
                row(OP, 0, EntityPayloadKind.ITEM, json),
                row(OP, 2, EntityPayloadKind.ITEM, json));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void fromRowsRejectsUnknownKindToken() {
        String json = "{\"kind\":\"nope\",\"type\":\"minecraft:item\",\"parent\":-1,\"depth\":0,\"fields\":{}}";
        List<EntityTransferPayload> rows = List.of(row(OP, 0, EntityPayloadKind.ITEM, json));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void fromRowsRejectsKindTokenDisagreeingWithRow() {
        String json = "{\"kind\":\"mob\",\"type\":\"minecraft:pig\",\"parent\":-1,\"depth\":0,\"fields\":{}}";
        List<EntityTransferPayload> rows = List.of(row(OP, 0, EntityPayloadKind.ITEM, json));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void fromRowsRejectsParentPointingForward() {
        String json0 = "{\"kind\":\"mob\",\"type\":\"minecraft:pig\",\"parent\":-1,\"depth\":0,\"fields\":{}}";
        String json1 = "{\"kind\":\"item\",\"type\":\"minecraft:item\",\"parent\":2,\"depth\":1,\"fields\":{}}";
        List<EntityTransferPayload> rows = List.of(
                row(OP, 0, EntityPayloadKind.MOB, json0),
                row(OP, 1, EntityPayloadKind.ITEM, json1));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void fromRowsRejectsMalformedJson() {
        List<EntityTransferPayload> rows = List.of(row(OP, 0, EntityPayloadKind.MOB, "not json {"));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void fromRowsRejectsEmptyInput() {
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(List.of()));
    }

    @Test
    void fromRowsRejectsExcessiveDepth() {
        String json = "{\"kind\":\"mob\",\"type\":\"minecraft:pig\",\"parent\":-1,\"depth\":99,\"fields\":{}}";
        List<EntityTransferPayload> rows = List.of(row(OP, 0, EntityPayloadKind.MOB, json));
        assertThrows(CodecException.class, () -> EntityCodec.fromRows(rows));
    }

    @Test
    void toRowsRequiresAtLeastRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityCodec.toRows(List.of(), OP, "nether", "minecraft:nether", null, T0));
    }

    @Test
    void rowsAreContiguousPreOrder() {
        List<EntityNode> graph = List.of(
                node(EntityPayloadKind.MINECART, -1, 0),
                node(EntityPayloadKind.MOB, 0, 1));
        List<EntityTransferPayload> rows = EntityCodec.toRows(graph, OP, "nether", "minecraft:nether", null, T0);
        List<EntityNode> back = EntityCodec.fromRows(rows);
        assertEquals(EntityPayloadKind.MINECART, back.get(0).kind());
        assertEquals(EntityPayloadKind.MOB, back.get(1).kind());
        assertTrue(back.get(1).parentSeq() == 0);
    }
}

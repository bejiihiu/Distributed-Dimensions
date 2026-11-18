package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;

import java.util.Objects;

/**
 * One serialized node of an entity assembly's passenger graph. Rows are written to the
 * payload store pre-order (root first); {@code parentSeq} links a node to its mount parent
 * and is {@code -1} for the root. {@code fields} carries the kind-specific payload — its
 * schema is owned by the serializer registry ({@link SerializerRegistry}).
 */
public record EntityNode(
        EntityPayloadKind kind,
        String entityTypeKey,
        int parentSeq,
        int depth,
        JsonObject fields) {

    public EntityNode {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entityTypeKey, "entityTypeKey");
        Objects.requireNonNull(fields, "fields");
        if (parentSeq < -1 || parentSeq >= 1024) {
            throw new IllegalArgumentException("parentSeq must be in [-1, 1024), got " + parentSeq);
        }
        if (depth < 0 || depth > 16) {
            throw new IllegalArgumentException("depth must be in [0, 16], got " + depth);
        }
    }
}

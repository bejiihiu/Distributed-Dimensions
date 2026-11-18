package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Entity;

/**
 * Reads the fields of an entity into a {@code fields} JSON object, and applies them back
 * onto a freshly spawned entity. Implementations must be total: any entity that passes
 * {@link #supports(Entity)} can be read and written without throwing; unrecoverable data
 * is reported through {@link EntityTransferMetrics#DEGRADED}, never silently dropped.
 */
public interface EntitySerializer {

    /** Stable lower-snake token matching {@link kz.bejiihiu.dimensions.common.model.EntityPayloadKind}. */
    String kindToken();

    /** The payload kind for the given entity (may vary within a serializer, e.g. minecart variants). */
    default kz.bejiihiu.dimensions.common.model.EntityPayloadKind kindOf(Entity entity) {
        for (kz.bejiihiu.dimensions.common.model.EntityPayloadKind kind
                : kz.bejiihiu.dimensions.common.model.EntityPayloadKind.values()) {
            if (kind.wireName().equals(kindToken())) {
                return kind;
            }
        }
        throw new IllegalStateException("serializers must map onto known payload kinds, got '" + kindToken() + "'");
    }

    /** Whether this serializer handles the given entity. */
    boolean supports(Entity entity);

    /** Extract the fields JSON for the given entity. */
    JsonObject read(Entity entity, EntityTransferMetrics metrics);

    /** Apply the fields JSON onto a freshly spawned entity. */
    void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics);
}

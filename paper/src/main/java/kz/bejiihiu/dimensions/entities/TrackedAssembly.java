package kz.bejiihiu.dimensions.entities;

import java.util.List;
import java.util.UUID;

/**
 * One tracked assembly waiting for the target's acknowledgement. The entities are kept by
 * reference (never by UUID lookup) — they are still loaded and alive in this world while
 * we wait; when the row count for {@code opId} reads zero, the target has spawned and
 * consumed everything, and the tracked entities are retired.
 */
public record TrackedAssembly(
        String opId,
        List<java.util.UUID> entityUuids,
        List<org.bukkit.entity.Entity> handles,
        long trackedAtMillis) {

    public TrackedAssembly {
        java.util.Objects.requireNonNull(opId, "opId");
        entityUuids = List.copyOf(entityUuids);
        handles = List.copyOf(handles);
    }
}

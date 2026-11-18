package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalDescriptor;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entry for the portal index. The index exists to accelerate "which portal is near these
 * coordinates?" — it is an optimization, never a source of truth. Callers must always verify
 * the actual world state before acting on a hit.
 */
public record PortalIndexEntry(
        UUID portalId,
        DimensionId dimension,
        PortalOrientation orientation,
        BlockPos origin,
        BlockPos innerMin,
        BlockPos innerMax,
        Instant createdAt,
        Instant updatedAt) {

    public PortalIndexEntry {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(innerMin, "innerMin");
        Objects.requireNonNull(innerMax, "innerMax");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (innerMin.x() > innerMax.x() || innerMin.y() > innerMax.y() || innerMin.z() > innerMax.z()) {
            throw new IllegalArgumentException("innerMin must not be greater than innerMax on any axis");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** Horizontal bounds overlap check against an inclusive XZ box (used by candidate filtering). */
    public boolean overlapsHorizontal(int minX, int maxX, int minZ, int maxZ) {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("empty search box");
        }
        return innerMax.x() >= minX && innerMin.x() <= maxX && innerMax.z() >= minZ && innerMin.z() <= maxZ;
    }

    /** Creates an entry from a freshly-built descriptor (createdAt == updatedAt). */
    public static PortalIndexEntry of(UUID portalId, DimensionId dimension, PortalDescriptor descriptor, Instant at) {
        return new PortalIndexEntry(portalId, dimension, descriptor.orientation(),
                descriptor.origin(), descriptor.innerMin(), descriptor.innerMax(), at, at);
    }
}

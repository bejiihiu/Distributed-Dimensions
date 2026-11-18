package kz.bejiihiu.dimensions.common.model;

import java.util.Objects;

/** A confirmed respawn point: which backend's dimension, where exactly, and what anchors it. */
public record RespawnPoint(DimensionId dimension, Position position, RespawnKind kind) {

    public RespawnPoint {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
    }
}

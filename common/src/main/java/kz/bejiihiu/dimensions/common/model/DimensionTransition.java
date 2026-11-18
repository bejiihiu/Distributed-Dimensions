package kz.bejiihiu.dimensions.common.model;

import java.util.Objects;

/**
 * Semantic tuple describing a "from -> to" dimension change, used when logging, journaling,
 * and emitting API events. Not a command — see {@link TransferRequest} for that.
 */
public record DimensionTransition(DimensionId from, DimensionId to, TransferCause cause) {

    public DimensionTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(cause, "cause");
        if (from.equals(to)) {
            throw new IllegalArgumentException("a dimension transition needs two different dimensions, got '" + from + "'");
        }
    }
}

package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;

import org.bukkit.World;

/**
 * Environment → canonical dimension mapping. Custom (datapack) dimensions have no
 * exposed resource key on the Bukkit {@code World}, so they fall back to the overworld
 * with {@code false} on the second element of the result; callers log a warning.
 */
final class FromDimension {

    private FromDimension() {
    }

    record Resolution(DimensionId dimension, boolean canonical) {
    }

    static Resolution of(World.Environment environment) {
        if (environment == null) {
            return new Resolution(DimensionId.OVERWORLD, false);
        }
        return switch (environment) {
            case NORMAL -> new Resolution(DimensionId.OVERWORLD, true);
            case NETHER -> new Resolution(DimensionId.NETHER, true);
            case THE_END -> new Resolution(DimensionId.THE_END, true);
            default -> new Resolution(DimensionId.OVERWORLD, false);
        };
    }
}

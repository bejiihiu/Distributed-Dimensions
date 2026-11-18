package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * The three slots a mirage stands in for. The role decides the advertised {@link
 * World.Environment} and the dimension a transfer into the mirage is routed toward.
 */
public enum MirageRole {

    OVERWORLD(World.Environment.NORMAL, DimensionId.OVERWORLD),
    NETHER(World.Environment.NETHER, DimensionId.NETHER),
    THE_END(World.Environment.THE_END, DimensionId.THE_END);

    private final World.Environment environment;
    private final DimensionId dimension;

    MirageRole(@NotNull World.Environment environment, @NotNull DimensionId dimension) {
        this.environment = environment;
        this.dimension = dimension;
    }

    public @NotNull World.Environment environment() {
        return environment;
    }

    /** The canonical dimension id this mirage is a stub of. */
    public @NotNull DimensionId dimension() {
        return dimension;
    }
}

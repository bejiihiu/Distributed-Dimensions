package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Descriptor of one mirage world: the canonical name, the Bukkit environment the
 * world advertises, the dimension this mirage stands in for (used as the routing
 * target when something teleports into it), and whether the world was created by
 * the plugin ({@code true}) or was already loaded when this backend started
 * ({@code false} — a real world on this backend, never overwritten).
 */
public record MirageWorld(@NotNull String name, @NotNull MirageRole role,
                          @NotNull World.Environment environment,
                          @NotNull DimensionId dimension, boolean createdByPlugin) {
}

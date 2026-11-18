package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.DimensionInfo;
import kz.bejiihiu.dimensions.api.DimensionRegistry;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.PluginConfig;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The published {@link DimensionRegistry}, built from the configured routing table
 * ({@code network.dimensions}) at registration time.
 *
 * <p>The snapshot mirrors the configuration verbatim — including slots a mis-wired
 * deployment might point at the wrong dimension (the transfer path refuses those; the
 * registry is a view, not a gate). Matching is case-sensitive on both axis, per the
 * api contract; {@link #all()} keeps the configuration order and is immutable.
 */
public final class ConfigDimensionRegistry implements DimensionRegistry {

    private final List<DimensionInfo> snapshot;

    public ConfigDimensionRegistry(@NotNull PluginConfig config) {
        this(config.network().dimensions());
    }

    public ConfigDimensionRegistry(@NotNull DimensionsSettings settings) {
        Objects.requireNonNull(settings, "settings");
        // order follows the configuration sections (overworld, nether, end) and is stable for the snapshot
        this.snapshot = List.of(
                info(settings.overworld()),
                info(settings.nether()),
                info(settings.end()));
    }

    private static DimensionInfo info(@NotNull DimensionTarget target) {
        return new DimensionInfo(DimensionId.of(target.dimension()), target.server());
    }

    @Override
    public @NotNull Optional<DimensionInfo> byServer(@NotNull String server) {
        Objects.requireNonNull(server, "server");
        return snapshot.stream().filter(entry -> entry.server().equals(server)).findFirst();
    }

    @Override
    public @NotNull Optional<DimensionInfo> byDimension(@NotNull DimensionId dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return snapshot.stream().filter(entry -> entry.dimension().equals(dimension)).findFirst();
    }

    @Override
    public @NotNull List<DimensionInfo> all() {
        return snapshot;
    }
}

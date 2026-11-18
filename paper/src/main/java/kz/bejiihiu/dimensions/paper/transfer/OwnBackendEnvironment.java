package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.paper.PaperServices;

import org.bukkit.World.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves the environment of the dimension THIS backend resides in, from configuration
 * alone — the backend has no platform API for "which dimension am I".
 *
 * <p>The match is by server name: the backend's {@code network.source-server} (the name the
 * proxy knows this backend by) is compared against the server of each
 * {@code network.dimensions} slot (overworld, nether, end, in that order). The dimension
 * key of the matching slot then maps to a Bukkit {@link Environment}:
 * {@code minecraft:overworld} → NORMAL, {@code minecraft:the_nether} → NETHER,
 * {@code minecraft:the_end} → THE_END.
 *
 * <p>Anything unresolvable — blank/absent {@code network.source-server}, no slot naming this
 * server, an unknown/custom dimension key — yields null, and the caller is expected to fall
 * back to the platform respawn world (the degraded but always-available path).
 */
public final class OwnBackendEnvironment {

    private OwnBackendEnvironment() {
    }

    /** Resolves against the live services holder; never throws. */
    @Nullable
    public static Environment resolve(PaperServices services) {
        try {
            return resolve(services.config());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves against the configuration snapshot; never throws. */
    @Nullable
    public static Environment resolve(PluginConfig config) {
        try {
            String source = config.network().sourceServer();
            if (source == null || source.isBlank()) {
                return null;
            }
            String server = source.trim();
            DimensionTarget slot = firstMatch(config.network().dimensions(), server);
            return slot == null ? null : environmentOf(slot.dimension());
        } catch (RuntimeException e) {
            // a config shape we did not anticipate must degrade to the platform respawn
            // world, not fail the arrival flow
            return null;
        }
    }

    private static @Nullable DimensionTarget firstMatch(DimensionsSettings dimensions, String server) {
        for (DimensionTarget slot : List.of(dimensions.overworld(), dimensions.nether(), dimensions.end())) {
            if (slot.server().equalsIgnoreCase(server)) {
                return slot;
            }
        }
        return null;
    }

    @Nullable
    static Environment environmentOf(String dimensionKey) {
        // the config grammar already pins dimension keys to lowercase namespaced ids
        return switch (dimensionKey) {
            case "minecraft:overworld" -> Environment.NORMAL;
            case "minecraft:the_nether" -> Environment.NETHER;
            case "minecraft:the_end" -> Environment.THE_END;
            default -> null;
        };
    }
}

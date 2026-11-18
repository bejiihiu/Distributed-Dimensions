package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.config.MirageNamesSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the mirage world names for this backend.
 *
 * <p>The Bukkit level-name convention is {@code <level-name>} (overworld),
 * {@code <level-name>_nether} and {@code <level-name>_the_end}. The bundled configuration
 * ships exactly those names for the default level-name {@code world}, and the configuration
 * documentation says the keys are "derived from the level-name". When the operator runs with
 * a non-default level-name without overriding {@code mirage.names}, the names must follow
 * the convention too — a server named {@code survival} would expose {@code survival_nether},
 * not {@code world_nether}, otherwise every plugin probing for the nether finds nothing.
 *
 * <p>Resolution: if the configured names differ from the bundled defaults at all, the
 * configuration wins verbatim (an explicit override, which the operator may set for
 * renamed worlds). Otherwise the names are computed from the server level-name.
 */
public record MirageNames(@NotNull String overworld, @NotNull String nether, @NotNull String end) {

    private static final String DEFAULT_OVERWORLD = "world";
    private static final String DEFAULT_NETHER = "world_nether";
    private static final String DEFAULT_END = "world_the_end";

    /**
     * @param configured the {@code mirage.names} configuration section (never null)
     * @param levelName  the server level-name (may be null/blank; falls back to {@code world})
     */
    public static @NotNull MirageNames resolve(@NotNull MirageNamesSettings configured, @Nullable String levelName) {
        String level = (levelName == null || levelName.isBlank()) ? DEFAULT_OVERWORLD : levelName.strip();
        boolean bundledDefaults = DEFAULT_OVERWORLD.equals(configured.overworld())
                && DEFAULT_NETHER.equals(configured.nether())
                && DEFAULT_END.equals(configured.end());
        if (bundledDefaults && !DEFAULT_OVERWORLD.equals(level)) {
            return fromLevelName(level);
        }
        return new MirageNames(configured.overworld(), configured.nether(), configured.end());
    }

    /** Names computed from a level-name, following the Bukkit convention. */
    public static @NotNull MirageNames fromLevelName(@NotNull String levelName) {
        String level = levelName.strip();
        if (level.isEmpty()) {
            level = DEFAULT_OVERWORLD;
        }
        return new MirageNames(level, level + "_nether", level + "_the_end");
    }
}

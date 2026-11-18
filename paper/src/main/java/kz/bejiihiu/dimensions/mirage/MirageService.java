package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.config.PluginConfig;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.GameRules;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the mirage (void stub) worlds of this backend: creation, configuration,
 * and lookup for the teleport guards.
 *
 * <p><b>Why mirages exist.</b> Each backend in a split-dimension network hosts only its
 * own real dimension. Third-party plugins (and plugins built against a single-server
 * mental model) probe for {@code world_nether} / {@code world_the_end} and expect non-null.
 * A mirage is a real Bukkit world — right environment, right dimension type, correct name —
 * but it contains nothing, never saves, and teleports into it are intercepted and turned
 * into cross-server transfers. It is deliberately opportunistic: if creation fails the
 * plugin logs {@code SEVERE} and keeps working; the mirage is simply missing.
 *
 * <p><b>Ordering.</b> Called from {@code onEnable} before any other feature wave ("step 0") —
 * after configuration load (it needs the file) but before persistence, schedulers, API
 * registration and every world-touching service. Failures never abort enable.
 *
 * <p><b>Creation rules.</b> For each canonical name (the level-name convention derived in
 * {@link MirageNames}) the mirage is created only when {@link Bukkit#getWorld(String)} returns
 * null — a real world of this backend is never shadowed. Mirages are {@code autoSave=false},
 * spawn pinned at (0, 100, 0), void generator, {@code the_void} biome, daylight/weather cycles
 * and mob spawning off, fixed noon, {@link Difficulty#PEACEFUL}. No chunk ticket is ever
 * requested for a mirage, so chunks never materialize; if a foreign plugin force-loads one
 * anyway it gets an instant empty air chunk and zero bytes on disk (no region files are
 * written because {@code autoSave} is off and nothing generates).
 *
 * <p><b>Not solvable dynamically, intentionally skipped:</b> per-world {@code paper-world.yml}
 * spawn-limits ({@code entities.spawning.spawn-limits.*: 0}) are read at world load from a file
 * the plugin cannot write blindly ({@code _version} must match the server's
 * {@code config/paper-world-defaults.yml}; a wrong value makes Paper reject the file). The
 * {@code doMobSpawning=false} gamerule covers natural spawning regardless. Admins who want
 * belt-and-braces limits are told exactly what to add in {@code docs/mirage-dimensions.md}.
 * {@code keep-spawn-loaded} no longer exists in Paper 26.3 (always-loaded spawn chunks were
 * removed), so there is nothing to set.
 *
 * <p><b>Lifecycle.</b> One instance per enable, self-registered in the Bukkit
 * {@link ServicesManager}. {@link #current()} is the lookup for feature waves; listeners
 * receive the instance directly at construction.
 */
public final class MirageService implements AutoCloseable {

    private final Logger logger;
    private final boolean enabled;
    private final long fixedTime;
    private final Map<String, MirageWorld> miragesByName;
    private final List<String> failures;
    private final MirageMetrics metrics = new MirageMetrics();

    private MirageService(@NotNull Logger logger, boolean enabled, long fixedTime,
                          @NotNull Map<String, MirageWorld> miragesByName, @NotNull List<String> failures) {
        this.logger = logger;
        this.enabled = enabled;
        this.fixedTime = fixedTime;
        this.miragesByName = Collections.unmodifiableMap(miragesByName);
        this.failures = List.copyOf(failures);
    }

    // ------------------------------------------------------------- creation

    /**
     * Creates the missing mirage worlds for this backend and registers the service.
     *
     * @param plugin the backend plugin (used for the service registration key)
     * @param config the loaded configuration snapshot
     * @param logger the plugin logger
     * @return the service; never null. When {@code mirage.enabled=false} the service is
     *         inert (no worlds, no guards should be registered)
     */
    public static @NotNull MirageService createMissingMirages(@NotNull Plugin plugin, @NotNull PluginConfig config,
                                                              @NotNull Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");

        boolean enabled = config.mirage().enabled();
        if (!enabled) {
            logger.info("mirage: disabled by configuration (mirage.enabled=false) — "
                    + "foreign dimensions simply do not exist on this backend");
            MirageService inert = new MirageService(logger, false, config.mirage().fixedTime(),
                    Map.of(), List.of());
            register(plugin, inert);
            return inert;
        }

        String levelName = defaultLevelName();
        MirageNames names = MirageNames.resolve(config.mirage().names(), levelName);
        long fixedTime = config.mirage().fixedTime();
        logger.info("mirage: resolving names (overworld={}, nether={}, end={}; level-name={})",
                names.overworld(), names.nether(), names.end(), levelName);

        Map<String, MirageWorld> byName = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        createIfMissing(plugin, logger, byName, failures, fixedTime,
                MirageRole.OVERWORLD, names.overworld());
        createIfMissing(plugin, logger, byName, failures, fixedTime,
                MirageRole.NETHER, names.nether());
        createIfMissing(plugin, logger, byName, failures, fixedTime,
                MirageRole.THE_END, names.end());

        long existing = byName.values().stream().filter(w -> !w.createdByPlugin()).count();
        long created = byName.values().stream().filter(MirageWorld::createdByPlugin).count();
        if (failures.isEmpty()) {
            logger.info("mirage: ready — {} created, {} already present (real worlds)", created, existing);
        } else {
            logger.error("SEVERE: mirage creation incomplete — {} failure(s): {}. "
                    + "The plugin keeps working; the affected dimensions are simply absent locally.",
                    failures.size(), String.join(" | ", failures));
        }
        warnPerWorldYamlResidualRisk(logger, byName);

        MirageService service = new MirageService(logger, true, fixedTime, byName, failures);
        register(plugin, service);
        return service;
    }

    private static @NotNull String defaultLevelName() {
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty() && worlds.get(0).getName() != null && !worlds.get(0).getName().isBlank()) {
                return worlds.get(0).getName();
            }
        } catch (RuntimeException e) {
            // fall through — the convention default is 'world'
        }
        return "world";
    }

    private static void createIfMissing(@NotNull Plugin plugin, @NotNull Logger logger,
                                        @NotNull Map<String, MirageWorld> byName, @NotNull List<String> failures,
                                        long fixedTime, @NotNull MirageRole role, @NotNull String name) {
        World preexisting = Bukkit.getWorld(name);
        if (preexisting != null) {
            logger.info("mirage: '{}' already loaded — treating as this backend's real world, not a mirage", name);
            byName.put(name, new MirageWorld(name, role, role.environment(), role.dimension(), false));
            return;
        }
        try {
            World created = new WorldCreator(name)
                    .environment(role.environment())
                    .type(WorldType.NORMAL)
                    .generator(new VoidChunkGenerator())
                    .biomeProvider(new VoidBiomeProvider())
                    .generateStructures(false)
                    .createWorld();
            if (created == null) {
                String failure = "world creation returned null for mirage '" + name + "'";
                logger.error("SEVERE: {}", failure);
                failures.add(failure);
                return;
            }
            configureMirage(logger, created, fixedTime);
            logger.info("mirage: created void stub '{}' (environment={}, dimension={})",
                    name, role.environment(), role.dimension());
            byName.put(name, new MirageWorld(name, role, role.environment(), role.dimension(), true));
        } catch (RuntimeException | LinkageError e) {
            String failure = "mirage '" + name + "' failed: " + e;
            logger.error("SEVERE: {}", failure, e);
            failures.add(failure);
        }
    }

    private static void configureMirage(@NotNull Logger logger, @NotNull World world, long fixedTime) {
        // No chunk tickets are ever requested for mirages by this plugin; autoSave=false
        // guarantees nothing reaches disk even if a foreign plugin force-loads a chunk.
        world.setAutoSave(false);
        world.setSpawnLocation(0, 100, 0);

        // Not every dimension type in 26.x owns a world clock (the nether does not —
        // "Cannot set time in world without world clock"). When there is no clock the
        // gamerule below is all that pins the time, and that is fine: the world is a void
        // nobody plays in. A missing clock must never abort the rest of the configuration.
        try {
            world.setTime(fixedTime);
        } catch (IllegalArgumentException e) {
            logger.info("mirage: '{}' has no world clock in this environment — fixed time skipped (daylight cycle is off anyway)",
                    world.getName());
        } catch (RuntimeException e) {
            // a genuinely broken world deserves a loud line, not a swallowed failure
            logger.warn("mirage: could not pin time on '{}': {}", world.getName(), e.toString());
        }

        world.setStorm(false);
        world.setThundering(false);
        try {
            world.setClearWeatherDuration(Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            logger.warn("mirage: could not set clear-weather duration on '{}': {}", world.getName(), e.toString());
        }
        // Peaceful: void world, no mobs, no accidental aggro; dragon cannot exist here.
        world.setDifficulty(Difficulty.PEACEFUL);
        setRule(logger, world, GameRules.ADVANCE_TIME, false);
        setRule(logger, world, GameRules.ADVANCE_WEATHER, false);
        setRule(logger, world, GameRules.SPAWN_MOBS, false);
    }

    private static <T> void setRule(@NotNull Logger logger, @NotNull World world,
                                    @NotNull org.bukkit.GameRule<T> rule, @NotNull T value) {
        boolean ok = world.setGameRule(rule, value);
        if (!ok) {
            logger.warn("mirage: could not set game rule {} to {} on '{}'", rule, value, world.getName());
        }
    }

    private static void warnPerWorldYamlResidualRisk(@NotNull Logger logger, @NotNull Map<String, MirageWorld> byName) {
        boolean anyCreated = byName.values().stream().anyMatch(MirageWorld::createdByPlugin);
        if (!anyCreated) {
            return;
        }
        logger.warn("mirage: spawn-limits and keep-spawn-loaded are NOT configurable from this plugin on Paper 26.3 "
                + "(always-loaded spawn chunks were removed; per-world spawn-limits live in each mirage's paper-world.yml, "
                + "which is read at world load only). Natural spawning is disabled by the SPAWN_MOBS gamerule and the void "
                + "generator regardless. If you want belt-and-braces limits, add to each mirage's paper-world.yml (copy "
                + "_version from config/paper-world-defaults.yml verbatim):\n"
                + "  entities:\n    spawning:\n      spawn-limits:\n        ambient: 0\n        axolotls: 0\n        creature: 0\n"
                + "        monster: 0\n        underground_water_creature: 0\n        water_ambient: 0\n        water_creature: 0\n"
                + "Residual risk if you don't: none in practice (void + SPAWN_MOBS=false), but the raw numbers will not be 0.");
    }

    private static void register(@NotNull Plugin plugin, @NotNull MirageService service) {
        ServicesManager services = plugin.getServer().getServicesManager();
        try {
            services.register(MirageService.class, service, plugin, ServicePriority.Normal);
        } catch (RuntimeException e) {
            // Registration must not abort enable; the service is still usable via the constructor reference.
            plugin.getSLF4JLogger().error("SEVERE: failed to register MirageService in the ServicesManager: {}", e.toString());
        }
    }

    /** @return the live service, or empty when the plugin is disabled / not registered. */
    public static @NotNull Optional<MirageService> current() {
        try {
            return Optional.ofNullable(Bukkit.getServicesManager().load(MirageService.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------- lookup

    public boolean enabled() {
        return enabled;
    }

    /** True when at least one mirage could not be created. */
    public boolean degraded() {
        return !failures.isEmpty();
    }

    /** Human-readable creation failures (empty when clean). */
    public @NotNull List<String> failures() {
        return failures;
    }

    /** Every tracked world (mirages and the pre-existing real worlds that occupy those slots). */
    public @NotNull List<MirageWorld> mirages() {
        return List.copyOf(miragesByName.values());
    }

    /** @return true when {@code world} is a stub this plugin created (never true for real worlds) */
    public boolean isMirage(@Nullable World world) {
        return world != null && isMirage(world.getName());
    }

    public boolean isMirage(@Nullable String worldName) {
        if (worldName == null) {
            return false;
        }
        MirageWorld mirage = miragesByName.get(worldName);
        return mirage != null && mirage.createdByPlugin();
    }

    /**
     * The dimension this mirage stands in for, or empty when the world is not a mirage.
     * Guards use this as the routing target of the intercepted teleport.
     */
    public @NotNull Optional<DimensionId> dimensionOf(@Nullable World world) {
        if (world == null) {
            return Optional.empty();
        }
        MirageWorld mirage = miragesByName.get(world.getName());
        if (mirage == null || !mirage.createdByPlugin()) {
            return Optional.empty();
        }
        return Optional.of(mirage.dimension());
    }

    public @NotNull MirageMetrics metrics() {
        return metrics;
    }

    public long fixedTime() {
        return fixedTime;
    }

    @Override
    public void close() {
        logger.debug("mirage: closing (worlds are left as-is and never deleted by the plugin)");
    }
}

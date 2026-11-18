package kz.bejiihiu.dimensions.common.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Holds the current {@link PluginConfig} snapshot and the health of the configuration.
 *
 * <p>Boot sequence ({@link #load()}):
 * <ol>
 *   <li>missing file → the bundled defaults are written to disk;</li>
 *   <li>file parsed → migrations applied (atomically saved back) → missing keys healed
 *       in memory from the bundled defaults (file <em>not</em> rewritten);</li>
 *   <li>unknown keys → collected into the report as dotted paths — never an error;</li>
 *   <li>any failure → the last-good snapshot is kept (or the bundled defaults, when
 *       nothing was loaded yet), the cause is exposed via {@link #health()} and
 *       {@link #load()} still succeeds; when no fallback exists at all,
 *       {@link #load()} throws {@link ConfigurationException}.</li>
 * </ol>
 *
 * <p>{@link #config()} never returns a partially built snapshot. All methods are
 * synchronized; load is a boot-time operation, not a hot path.
 */
public final class ConfigurationService {

    private final Environment environment;
    private final EnvLookup env;
    private final Consumer<String> diagnostics;

    private PluginConfig snapshot;
    private ConfigurationException lastError;
    private LoadReport report;

    /** Service with the real process environment and no diagnostic sink. */
    public ConfigurationService(Environment environment) {
        this(environment, EnvLookup.system(), message -> { });
    }

    public ConfigurationService(Environment environment, EnvLookup env) {
        this(environment, env, message -> { });
    }

    /**
     * @param diagnostics receives human-readable lines (warnings, migrations, broken
     *                    config) for the platform logger; never carries secret values
     */
    public ConfigurationService(Environment environment, EnvLookup env, Consumer<String> diagnostics) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        this.env = java.util.Objects.requireNonNull(env, "env");
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
    }

    /** Convenience for platform bootstrap: {@code configDir/config.yml} with the bundled defaults. */
    public static ConfigurationService forDirectory(java.nio.file.Path configDir) {
        return new ConfigurationService(Environment.resolve(configDir));
    }

    /** Boot (or re-boot) the configuration. See class javadoc for the exact sequence. */
    public synchronized PluginConfig load() {
        try {
            ConfigLoader.Loaded loaded = ConfigLoader.load(environment, env);
            applySuccess(loaded);
            return loaded.config();
        } catch (ConfigurationException broken) {
            return applyFailure(broken);
        }
    }

    /** The current effective snapshot. */
    public synchronized PluginConfig config() {
        if (snapshot == null) {
            throw new IllegalStateException("configuration is unavailable: "
                    + (lastError != null ? lastError.getMessage() : "load() has not been called yet"));
        }
        return snapshot;
    }

    /** Empty when the last {@link #load()} succeeded; present with details when the file is broken. */
    public synchronized Optional<ConfigurationException> health() {
        return Optional.ofNullable(lastError);
    }

    /** True when the last {@link #load()} succeeded with no broken-file fallback. */
    public synchronized boolean isHealthy() {
        return lastError == null && snapshot != null;
    }

    /** What the last {@link #load()} did; {@code null} before the first {@link #load()}. */
    public synchronized LoadReport report() {
        return report;
    }

    // ------------------------------------------------------------------

    private void applySuccess(ConfigLoader.Loaded loaded) {
        snapshot = loaded.config();
        lastError = null;
        report = new LoadReport(loaded.source(), loaded.fileCreated(), loaded.config().configVersion(),
                loaded.migratedFrom(), loaded.migratedTo(), loaded.warnings(), loaded.notes());
        for (String note : loaded.notes()) {
            diagnostics.accept("config: " + note);
        }
        for (String warning : loaded.warnings()) {
            diagnostics.accept("config: unknown key '" + warning + "' — ignored");
        }
    }

    private PluginConfig applyFailure(ConfigurationException broken) {
        if (snapshot != null) {
            report = new LoadReport(ConfigSource.LAST_GOOD, false, snapshot.configVersion(), -1, -1,
                    List.of(), List.of("kept last-good configuration: " + broken.getMessage()));
            lastError = broken;
            diagnostics.accept("config: " + broken.getMessage() + " — keeping last-good configuration");
            return snapshot;
        }
        try {
            PluginConfig fallback = ConfigLoader.defaultsSnapshot(environment, env);
            snapshot = fallback;
            lastError = broken;
            report = new LoadReport(ConfigSource.DEFAULTS_FALLBACK, false, fallback.configVersion(), -1, -1,
                    List.of(), List.of("fell back to bundled defaults: " + broken.getMessage()));
            diagnostics.accept("config: " + broken.getMessage() + " — using bundled defaults");
            return fallback;
        } catch (ConfigurationException fallbackBroken) {
            ConfigurationException combined = new ConfigurationException(
                    broken.getMessage() + " (bundled-defaults fallback also failed: " + fallbackBroken.getMessage() + ")",
                    broken);
            lastError = combined;
            report = new LoadReport(ConfigSource.DEFAULTS_FALLBACK, false, -1, -1, -1,
                    List.of(), List.of(combined.getMessage()));
            diagnostics.accept("config: " + combined.getMessage());
            throw combined;
        }
    }
}

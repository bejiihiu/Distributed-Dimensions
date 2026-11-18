package kz.bejiihiu.dimensions.common.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where the configuration lives and which bundled defaults to write on first start.
 *
 * <p>Paper passes its data directory, Velocity passes {@code @DataDirectory} —
 * both end up as {@link #resolve(Path)} or {@code new Environment(dir.resolve("config.yml"))}.
 * The path may be absolute or relative; the loader absolutizes it.
 *
 * @param configPath      the YAML file to read (and write, on first start or migration)
 * @param defaultsResource classpath resource with the bundled defaults, normally
 *                         {@code "config.yml"} — overridable only for tests
 */
public record Environment(Path configPath, String defaultsResource) {

    /** The defaults resource every platform ships. */
    public static final String DEFAULT_RESOURCE = "config.yml";

    public Environment {
        Objects.requireNonNull(configPath, "configPath");
        if (defaultsResource == null || defaultsResource.isBlank()) {
            throw new IllegalArgumentException("defaultsResource must not be blank");
        }
    }

    /** Environment for {@code <configDir>/config.yml} with the bundled defaults. */
    public static Environment resolve(Path configDir) {
        return resolve(configDir, DEFAULT_RESOURCE);
    }

    /** Environment for {@code <configDir>/config.yml} with an overridden defaults resource. */
    public static Environment resolve(Path configDir, String defaultsResource) {
        Objects.requireNonNull(configDir, "configDir");
        return new Environment(configDir.resolve("config.yml"), defaultsResource);
    }
}

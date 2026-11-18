package kz.bejiihiu.dimensions.common.config;

import java.util.Map;
import java.util.Optional;

/**
 * Indirection layer for environment variables, injectable so tests (and platforms
 * with their own scoping) don't depend on the real process environment.
 *
 * <p>Values are trimmed; a blank value counts as unset. Implementations never
 * return or log anything except the variable name at the call site — the caller
 * decides what fails.
 */
@FunctionalInterface
public interface EnvLookup {

    /** The trimmed value of {@code name}, or empty when unset/blank. */
    Optional<String> get(String name);

    /** Environment lookup backed by {@link System#getenv(String)}. */
    static EnvLookup system() {
        return name -> Optional.ofNullable(System.getenv(name))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    /** Fixed-map lookup for tests. Blank values count as unset, like the system lookup. */
    static EnvLookup fixed(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}

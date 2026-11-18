package kz.bejiihiu.dimensions.common.persistence;

import java.util.Optional;

/**
 * Env-var indirection for secrets. The config layer (or the operator's scripts) resolves the
 * actual values; nothing else in the codebase reads the environment for credentials.
 */
public final class EnvSecrets {

    private EnvSecrets() {
    }

    /**
     * Returns the trimmed value of {@code name}, or throws
     * {@link IllegalStateException} with an actionable message when it is unset or blank.
     */
    public static String required(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env var name must not be blank");
        }
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "required environment variable '" + name + "' is not set or is blank — "
                            + "set it in the service environment (or in the unit/container definition) before starting");
        }
        return value.trim();
    }

    /** The trimmed value of {@code name}, or empty when unset/blank. */
    public static Optional<String> optional(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(System.getenv(name)).map(String::trim).filter(s -> !s.isEmpty());
    }
}

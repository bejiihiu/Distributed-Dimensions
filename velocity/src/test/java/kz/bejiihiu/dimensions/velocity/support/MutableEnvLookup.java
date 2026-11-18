package kz.bejiihiu.dimensions.velocity.support;

import kz.bejiihiu.dimensions.common.config.EnvLookup;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link EnvLookup} backed by a mutable map, for tests that need the "environment changes
 * between boot and reload" scenario. Blank values count as unset, like the system lookup.
 */
public final class MutableEnvLookup implements EnvLookup {

    private final Map<String, String> values = new HashMap<>();

    public void set(String name, String value) {
        values.put(name, value);
    }

    public void unset(String name) {
        values.remove(name);
    }

    @Override
    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}

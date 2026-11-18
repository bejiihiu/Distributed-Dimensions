package kz.bejiihiu.dimensions.common.config;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One routing target: the proxy server that holds {@code dimension} and the
 * namespaced dimension id it serves ("minecraft:the_nether").
 *
 * <p>{@code server} uses the same grammar as the wire protocol's server names.
 */
public record DimensionTarget(String server, String dimension) {

    private static final Pattern SERVER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public DimensionTarget {
        Objects.requireNonNull(server, "server");
        if (!SERVER.matcher(server).matches()) {
            throw new IllegalArgumentException(
                    "server must match [A-Za-z0-9_-]{1,64}, got '" + server + "'");
        }
        Objects.requireNonNull(dimension, "dimension");
        if (!NAMESPACED_ID.matcher(dimension).matches()) {
            throw new IllegalArgumentException(
                    "dimension must be a namespaced id like 'minecraft:the_nether', got '" + dimension + "'");
        }
    }
}

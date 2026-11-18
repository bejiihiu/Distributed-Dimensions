package kz.bejiihiu.dimensions.api;

import java.util.Objects;

/**
 * A read-only snapshot of one configured dimension-to-backend mapping, as seen by the running
 * implementation on this backend.
 *
 * <p>Instances are immutable and safe to share. Values are captured at call time: if the plugin
 * configuration is reloaded while your plugin holds a snapshot, the snapshot does not change.
 * Call {@link DimensionRegistry#all()} again to observe the current configuration.
 *
 * @param dimension the dimension this entry describes
 * @param server the backend server name that serves the dimension, exactly as configured
 *               (matching the backend name registered on the proxy, case-sensitive)
 */
public record DimensionInfo(DimensionId dimension, String server) {

    public DimensionInfo {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(server, "server");
        server = server.strip();
        if (server.isEmpty()) {
            throw new IllegalArgumentException("server must not be blank");
        }
    }
}

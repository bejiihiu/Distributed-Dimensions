package kz.bejiihiu.dimensions.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the configured dimension-to-backend mapping, as seen by the running
 * implementation on this backend.
 *
 * <p>Returned snapshots reflect the plugin configuration at call time. They are immutable;
 * a snapshot never changes after it is returned. Backends are identified by the server names
 * registered on the proxy; matching is case-sensitive.
 */
public interface DimensionRegistry {

    /**
     * Looks up the dimension served by a given backend server.
     *
     * @param server the backend server name, exactly as configured
     * @return the mapping, or empty if no configured dimension is served by {@code server}
     */
    @NotNull Optional<DimensionInfo> byServer(@NotNull String server);

    /**
     * Looks up the backend that serves a given dimension.
     *
     * @param dimension the dimension to look up
     * @return the mapping, or empty if the dimension is not configured
     */
    @NotNull Optional<DimensionInfo> byDimension(@NotNull DimensionId dimension);

    /**
     * Lists every configured dimension-to-backend mapping.
     *
     * @return an unmodifiable snapshot of all configured mappings; the order follows the plugin
     *         configuration and is stable for the returned snapshot
     */
    @NotNull List<DimensionInfo> all();
}

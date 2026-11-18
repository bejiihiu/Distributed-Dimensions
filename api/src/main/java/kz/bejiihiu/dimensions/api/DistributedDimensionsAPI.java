package kz.bejiihiu.dimensions.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point of the Distributed Dimensions public api.
 *
 * <p>Obtain the running implementation from any thread:
 *
 * <pre>{@code
 * DistributedDimensionsAPI api = DistributedDimensionsAPI.getInstance();
 * TransferManager tm = api.transfers();
 * DimensionRegistry dims = api.dimensions();
 * PortalRegistry portals = api.portals();
 * }</pre>
 *
 * <p>The implementation is registered by the Distributed Dimensions backend plugin during
 * startup and unregistered during shutdown. Calling {@link #getInstance()} before the plugin
 * registers (or after it unregisters) throws {@link IllegalStateException} — if your plugin
 * depends on this api, declare a dependency on the plugin in your own plugin descriptor so the
 * backend plugin enables first.
 *
 * <p>Third-party plugins must not subclass or implement this class.
 */
public abstract class DistributedDimensionsAPI {

    private static final AtomicReference<DistributedDimensionsAPI> INSTANCE = new AtomicReference<>();

    /** Protected to prevent direct instantiation outside the implementation. */
    protected DistributedDimensionsAPI() {
    }

    /**
     * The transfer service of the running implementation.
     *
     * @return the transfer service; never {@code null}
     */
    @NotNull
    public abstract TransferManager transfers();

    /**
     * The dimension registry of the running implementation.
     *
     * @return the dimension registry; never {@code null}
     */
    @NotNull
    public abstract DimensionRegistry dimensions();

    /**
     * The portal cooldown view of the running implementation.
     *
     * @return the portal registry; never {@code null}
     */
    @NotNull
    public abstract PortalRegistry portals();

    /**
     * The running api instance.
     *
     * @return the registered implementation; never {@code null}
     * @throws IllegalStateException if the backend plugin has not registered yet or has already
     *                               unregistered
     */
    @NotNull
    public static DistributedDimensionsAPI getInstance() {
        DistributedDimensionsAPI api = INSTANCE.get();
        if (api == null) {
            throw new IllegalStateException(
                    "Distributed Dimensions is not available on this backend: "
                            + "the plugin has not registered its api yet (is the plugin enabled?)");
        }
        return api;
    }

    /**
     * Registers the running implementation. Called by the backend plugin during startup, exactly
     * once, before any transfer is initiated.
     *
     * @param implementation the implementation to register
     * @throws NullPointerException if {@code implementation} is {@code null}
     * @throws IllegalStateException if another implementation is already registered
     */
    public static void register(@NotNull DistributedDimensionsAPI implementation) {
        Objects.requireNonNull(implementation, "implementation");
        if (!INSTANCE.compareAndSet(null, implementation)) {
            throw new IllegalStateException("a DistributedDimensionsAPI implementation is already registered");
        }
    }

    /**
     * Unregisters the running implementation so that {@link #getInstance()} fails again. Called
     * by the backend plugin during shutdown.
     *
     * <p>Most third-party plugins never need this; it exists for clean shutdown sequencing and
     * for test isolation.
     */
    public static void unregister() {
        INSTANCE.set(null);
    }
}

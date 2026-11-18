package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.DistributedDimensionsAPI;
import kz.bejiihiu.dimensions.interop.CrossServerTransfer;
import kz.bejiihiu.dimensions.paper.PaperServices;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Registers (and unregisters) the public api for this backend.
 *
 * <p>The api is registered exactly once, late in enable — after mirages exist and the guards
 * are in place — and unregistered first on disable, before any other teardown touches the
 * services third parties may race against. Registration is a CAS inside
 * {@link DistributedDimensionsAPI#register}; a double registration (e.g. a stray reload) is a
 * logged error, not a crash.
 *
 * <p>When {@code api.enabled=false}, nothing is registered and
 * {@code DistributedDimensionsAPI.getInstance()} throws {@code IllegalStateException}, exactly
 * as on a backend without the plugin — third parties probing must treat that as "api absent",
 * which is also what happens when they hit a backend without the plugin at all.
 */
public final class ApiBridge {

    private final Logger logger;
    private boolean registered;

    private ApiBridge(@NotNull Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers the api when {@code api.enabled} is set.
     *
     * @return the bridge, or {@code null} when the api is disabled by configuration
     */
    public static @Nullable ApiBridge registerIfEnabled(@NotNull JavaPlugin plugin,
                                                        @NotNull PaperServices services,
                                                        @NotNull CrossServerTransfer executor) {
        Logger logger = plugin.getSLF4JLogger();
        if (!services.config().api().enabled()) {
            logger.info("compat api: disabled by configuration (api.enabled=false) — not registering");
            return null;
        }

        PaperDistributedDimensionsAPI impl = new PaperDistributedDimensionsAPI(
                new PaperTransferManager(services, executor),
                new ConfigDimensionRegistry(services.config()),
                new PaperPortalRegistry(new PdcCooldownStore(plugin)));
        try {
            DistributedDimensionsAPI.register(impl);
        } catch (IllegalStateException e) {
            // another implementation is already registered — loud, not fatal
            logger.error("SEVERE: api already registered by another implementation: {}", e.getMessage());
            return null;
        }
        logger.info("compat api: registered (transfers + dimensions + portals); api.compat.grant-advancements={}, api.compat.fire-synthetic-events={}",
                services.config().api().compat().grantAdvancements(),
                services.config().api().compat().fireSyntheticEvents());
        ApiBridge bridge = new ApiBridge(logger);
        bridge.registered = true;
        return bridge;
    }

    /** Unregisters the api. Idempotent; safe to call from onDisable. */
    public void unregister() {
        if (!registered) {
            return;
        }
        try {
            DistributedDimensionsAPI.unregister();
            logger.debug("compat api: unregistered");
        } catch (RuntimeException e) {
            logger.error("compat api: unregister failed: {}", e.toString());
        } finally {
            registered = false;
        }
    }
}

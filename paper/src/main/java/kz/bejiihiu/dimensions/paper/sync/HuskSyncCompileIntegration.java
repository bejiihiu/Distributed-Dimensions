package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Level A: compile-time binding to HuskSync's public API (the only sync plugin
 * with a documented wrapper event). Registers a {@code MONITOR}-priority listener
 * on {@code net.william278.husksync.event.BukkitSyncCompleteEvent}.
 *
 * <p><b>Linkage safety.</b> This class never names HuskSync types; the listener
 * lives in {@link HuskSyncSyncBridge}, which is only loaded (and registered) after
 * both checks pass: the {@code HuskSync} plugin is installed, and the event class
 * resolves from that plugin's own classloader and is a Bukkit {@link Event}. On
 * servers without HuskSync — or with an API-incompatible one — {@link #tryBind}
 * reports unavailability and the caller degrades. Subclass of the bridge is never
 * touched in that path.
 *
 * <p>The event also fires on admin-forced data restores of online players; that is
 * harmless here because completions are attributed to waiters that are actually
 * pending (see {@link AbstractArmedIntegration}).
 */
public final class HuskSyncCompileIntegration extends AbstractArmedIntegration {

    private HuskSyncCompileIntegration(Schedulers schedulers, Predicate<UUID> onlineCheck,
            long waitTimeoutMillis, LongSupplier clock, Logger logger) {
        super(schedulers, onlineCheck, waitTimeoutMillis, 0, clock, logger,
                SyncReadiness.READY, Mode.EVENT_BOUND, SyncCause.EVENT, true);
    }

    /**
     * Cheap presence pre-check used by strategy selection. Never throws.
     */
    static boolean probe(Plugin plugin) {
        try {
            Plugin husk = plugin.getServer().getPluginManager().getPlugin("HuskSync");
            if (husk == null) {
                return false;
            }
            Class<?> cls = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent",
                    false, husk.getClass().getClassLoader());
            return Event.class.isAssignableFrom(cls);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bind the integration: presence/API checks, bridge registration. Returns empty
     * when HuskSync is absent, the API is incompatible, or the listener cannot be
     * registered — the caller decides the fallback. Never throws.
     */
    public static Optional<HuskSyncCompileIntegration> tryBind(Plugin plugin, Schedulers schedulers,
            long waitTimeoutMillis, Predicate<UUID> onlineCheck, LongSupplier clock, Logger logger) {
        PluginManager manager = plugin.getServer().getPluginManager();
        Plugin husk = manager.getPlugin("HuskSync");
        if (husk == null) {
            logger.info("sync: HuskSync not installed — level A unavailable");
            return Optional.empty();
        }
        final Class<?> eventClass;
        try {
            eventClass = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent",
                    false, husk.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(eventClass)) {
                logger.warn("sync: {} exists but is not a Bukkit event — HuskSync API incompatible, degrading",
                        eventClass.getName());
                return Optional.empty();
            }
            eventClass.getMethod("getHandlerList");
        } catch (Throwable t) {
            logger.warn("sync: HuskSync is installed but its sync-complete event is unavailable ({}); "
                            + "need the public API of HuskSync >= 3.2 — degrading", describe(t));
            return Optional.empty();
        }

        HuskSyncCompileIntegration integration = new HuskSyncCompileIntegration(
                schedulers, onlineCheck, waitTimeoutMillis, clock, logger);
        HuskSyncSyncBridge bridge = new HuskSyncSyncBridge(integration);
        try {
            manager.registerEvents(bridge, plugin);
        } catch (Throwable t) {
            logger.warn("sync: could not register the HuskSync completion listener: {} — degrading",
                    describe(t));
            return Optional.empty();
        }
        integration.listenerBridge = bridge;
        logger.info("sync: HuskSync API bridge armed on {} (MONITOR priority)", eventClass.getName());
        return Optional.of(integration);
    }

    @Override
    public String id() {
        return "husksync-api";
    }

    @Override
    public String describe() {
        return "HuskSync public API listener on net.william278.husksync.event.BukkitSyncCompleteEvent";
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

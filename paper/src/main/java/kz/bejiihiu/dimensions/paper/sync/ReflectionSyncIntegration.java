package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import kz.bejiihiu.dimensions.common.config.SyncSettings;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Level B: reflection listener on a configurable sync-completion event for plugins
 * without a compile-time API. Everything is loaded, validated and invoked
 * reflectively — generics-free and linkage-safe, so a missing or renamed class
 * degrades cleanly instead of breaking the server.
 *
 * <p>Configuration ({@code sync.reflection.*}):
 * <ul>
 *   <li>{@code event-class} — fully qualified {@code org.bukkit.event.Event} subclass
 *       to listen for. HuskSync: {@code net.william278.husksync.event.BukkitSyncCompleteEvent}.</li>
 *   <li>{@code getter} — no-arg method on the event returning the player. Accepted
 *       shapes, in order: a {@link UUID} (used directly), a {@link Player}
 *       ({@code getUniqueId()}), or any object exposing {@code getUuid()}
 *       (e.g. HuskSync's {@code getUser()} result). Default {@code getPlayer}.</li>
 * </ul>
 *
 * <p>Registration goes through {@link PluginManager#registerEvent(Class, Listener, EventPriority, EventExecutor, Plugin)}
 * with a generic {@link EventExecutor} bridge, at {@link EventPriority#MONITOR} —
 * observation only, never modifies anything. The UUID extraction is fully
 * Throwable-guarded; an unresolvable event fails the wait loudly at its timeout.
 */
public final class ReflectionSyncIntegration extends AbstractArmedIntegration {

    private final Class<?> eventClass;
    private final Method getter;
    private final String eventClassName;
    private final AtomicBoolean extractionWarned = new AtomicBoolean();

    ReflectionSyncIntegration(Schedulers schedulers, Predicate<UUID> onlineCheck,
            long waitTimeoutMillis, LongSupplier clock, Logger logger,
            Class<?> eventClass, Method getter, String eventClassName) {
        super(schedulers, onlineCheck, waitTimeoutMillis, 0, clock, logger,
                SyncReadiness.READY, Mode.EVENT_BOUND, SyncCause.EVENT, true);
        this.eventClass = eventClass;
        this.getter = getter;
        this.eventClassName = eventClassName;
    }

    /**
     * Bind the reflection listener. Returns empty when not configured, the class
     * cannot be loaded/validated, the getter is missing, or registration fails; the
     * caller decides the fallback. Never throws.
     */
    public static Optional<ReflectionSyncIntegration> tryBind(Plugin plugin, Schedulers schedulers,
            SyncSettings settings, Predicate<UUID> onlineCheck, LongSupplier clock, Logger logger) {
        String className = settings.reflectionEventClass();
        if (className.isEmpty()) {
            logger.info("sync: sync.plugin=reflection but sync.reflection.event-class is empty — level B unavailable");
            return Optional.empty();
        }
        PluginManager manager = plugin.getServer().getPluginManager();

        Class<?> cls = findEventClass(className, manager, plugin.getClass().getClassLoader());
        if (cls == null) {
            logger.warn("sync: reflection event class '{}' not found on any installed plugin's classpath — degrading",
                    className);
            return Optional.empty();
        }
        if (!Event.class.isAssignableFrom(cls)) {
            logger.warn("sync: '{}' does not extend org.bukkit.event.Event — degrading", className);
            return Optional.empty();
        }
        final Method getter;
        try {
            getter = cls.getMethod(settings.reflectionGetter());
        } catch (Throwable t) {
            logger.warn("sync: event class '{}' has no public no-arg '{}'() method ({}) — degrading",
                    className, settings.reflectionGetter(), describe(t));
            return Optional.empty();
        }

        ReflectionSyncIntegration integration = new ReflectionSyncIntegration(
                schedulers, onlineCheck, settings.waitTimeout().toMillis(), clock, logger, cls, getter, className);
        Listener marker = new Listener() {
        };
        EventExecutor executor = (listener, event) -> integration.handleEvent(event);
        try {
            manager.registerEvent(cls.asSubclass(Event.class), marker, EventPriority.MONITOR, executor, plugin);
        } catch (Throwable t) {
            logger.warn("sync: could not register the reflection listener on '{}': {} — degrading",
                    className, describe(t));
            return Optional.empty();
        }
        integration.listenerBridge = marker;
        logger.info("sync: reflection listener armed on {} (MONITOR priority, getter {}())",
                className, settings.reflectionGetter());
        return Optional.of(integration);
    }

    /** Event-executor bridge entry point. Never throws; events for other classes are ignored. */
    void handleEvent(Event event) {
        if (event == null || !eventClass.isInstance(event)) {
            return;
        }
        UUID uuid = extractUuid(event);
        if (uuid == null) {
            if (extractionWarned.compareAndSet(false, true)) {
                logger().warn("sync: could not extract a player UUID from {} via '{}'() — "
                                + "expected a Player, a UUID or an object with getUuid(); waits will fail at the timeout",
                        eventClassName, getter.getName());
            }
            return;
        }
        completeIfPending(uuid, SyncCause.EVENT);
    }

    /** Extraction, fully Throwable-guarded. Package-private for tests. */
    UUID extractUuid(Event event) {
        try {
            Object result = getter.invoke(event);
            return coerceUuid(result);
        } catch (Throwable t) {
            return null;
        }
    }

    private static UUID coerceUuid(Object result) throws ReflectiveOperationException {
        if (result instanceof UUID uuid) {
            return uuid;
        }
        if (result instanceof Player player) {
            return player.getUniqueId();
        }
        if (result == null) {
            return null;
        }
        try {
            Method getUuid = result.getClass().getMethod("getUuid");
            Object inner = getUuid.invoke(result);
            return inner instanceof UUID uuid ? uuid : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "reflection";
    }

    @Override
    public String describe() {
        return "reflection listener on " + eventClassName + " (getter " + getter.getName() + "())";
    }

    private static Class<?> findEventClass(String className, PluginManager manager, ClassLoader ownLoader) {
        for (Plugin installed : manager.getPlugins()) {
            try {
                return Class.forName(className, false, installed.getClass().getClassLoader());
            } catch (Throwable ignored) {
                // not on this plugin's classpath — try the next one
            }
        }
        try {
            return Class.forName(className, false, ownLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

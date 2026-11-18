package kz.bejiihiu.dimensions.common.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Integration points for the external player-data sync plugin. {@code waitForSync}
 * arms an asynchronous wait for the external plugin to acknowledge the sync on the
 * target backend; {@code plugin} selects the strategy, or {@code "auto"} to detect
 * it. The remaining keys tune the wait: {@code waitTimeout} bounds every wait
 * (event-driven and delay-based alike), {@code waitTicks} is the fixed delay used
 * by the delay-based strategies, and the {@code reflection*} pair describes the
 * event-class listener used by the reflection strategy.
 */
public record SyncSettings(boolean enabled, boolean waitForSync, String plugin,
        Duration waitTimeout, int waitTicks,
        String reflectionEventClass, String reflectionGetter) {

    /** Maximum accepted length for the configured reflection event class name. */
    static final int MAX_CLASS_NAME_LENGTH = 256;

    public SyncSettings {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("sync.plugin must not be blank");
        }
        plugin = plugin.trim();

        Objects.requireNonNull(waitTimeout, "waitTimeout must not be null");
        if (waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "sync.wait-timeout must be > 0 ms, got " + waitTimeout.toMillis());
        }
        if (waitTicks < 0) {
            throw new IllegalArgumentException("sync.wait-ticks must be >= 0, got " + waitTicks);
        }

        reflectionEventClass = reflectionEventClass == null ? "" : reflectionEventClass.trim();
        if (reflectionEventClass.length() > MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("sync.reflection.event-class is too long ("
                    + reflectionEventClass.length() + " > " + MAX_CLASS_NAME_LENGTH + " chars)");
        }
        if (!reflectionEventClass.isEmpty()
                && !reflectionEventClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException(
                    "sync.reflection.event-class must be a fully qualified class name, got '"
                            + reflectionEventClass + "'");
        }

        if (reflectionGetter == null || reflectionGetter.isBlank()) {
            throw new IllegalArgumentException("sync.reflection.getter must not be blank");
        }
        reflectionGetter = reflectionGetter.trim();
        if (!reflectionGetter.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException(
                    "sync.reflection.getter must be a simple method name, got '" + reflectionGetter + "'");
        }
    }

    /** True when a reflection listener class is configured (the reflection strategy can bind). */
    public boolean reflectionConfigured() {
        return !reflectionEventClass.isEmpty();
    }
}

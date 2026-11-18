package kz.bejiihiu.dimensions.common.util;

import java.time.Instant;
import java.util.Objects;

/**
 * Injectable time source. Production code never calls {@code System.currentTimeMillis()}
 * directly — everything takes a {@link Clock} so tests can freeze time (TTL expiry of the
 * portal index, journal timestamps, cooldown deadlines).
 *
 * <p>Deliberately minimal next to {@code java.time.Clock}: the codebase only ever wants a
 * millisecond epoch, and this interface adds a {@code fixed(...)} factory for tests without
 * dragging zone semantics in.
 */
public interface Clock {

    /** Milliseconds since the epoch (UTC). */
    long currentTimeMillis();

    default Instant now() {
        return Instant.ofEpochMilli(currentTimeMillis());
    }

    static Clock system() {
        return System::currentTimeMillis;
    }

    /** A clock frozen at {@code instant} — for deterministic tests. */
    static Clock fixed(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        long fixed = instant.toEpochMilli();
        return () -> fixed;
    }
}

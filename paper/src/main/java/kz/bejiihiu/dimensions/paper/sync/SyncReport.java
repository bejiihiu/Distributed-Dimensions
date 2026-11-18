package kz.bejiihiu.dimensions.paper.sync;

import java.util.Objects;

/**
 * Outcome of one sync wait, handed to the arrival flow when the future completes
 * normally.
 *
 * @param strategy       stable id of the strategy that produced the result
 *                       (e.g. {@code husksync-api}, {@code presence-delay},
 *                       {@code degraded-fallback}, {@code disabled})
 * @param durationMillis wall-clock time the wait took
 * @param degraded       true when nothing about the data could actually be confirmed
 *                       (degraded strategy, or waits disabled by configuration).
 *                       Callers should surface this rather than assume data arrived.
 */
public record SyncReport(String strategy, long durationMillis, boolean degraded) {

    public SyncReport {
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("strategy must not be blank");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be >= 0, got " + durationMillis);
        }
        strategy = strategy.trim();
    }
}

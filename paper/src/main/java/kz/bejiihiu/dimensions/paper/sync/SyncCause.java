package kz.bejiihiu.dimensions.paper.sync;

/** How a pending wait was satisfied. */
public enum SyncCause {

    /** The external sync plugin fired its completion event (or already had, before the wait armed). */
    EVENT,

    /**
     * No completion event exists; readiness is assumed after the fixed
     * {@code sync.wait-ticks} delay because the detected sync plugin gates login on applied data.
     */
    FIXED_DELAY,

    /**
     * No usable sync signal at all: the fixed delay elapsed with no plugin
     * confirmation. Treated as unconfirmed; the {@link SyncReport} for this transfer
     * is flagged degraded.
     */
    DEGRADED,

    /** sync waits were disabled by configuration; nothing was ever confirmed. */
    DISABLED
}

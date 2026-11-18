package kz.bejiihiu.dimensions.paper.sync;

/**
 * Current readiness posture of a {@link SyncIntegration}.
 *
 * <p>Only the event-driven strategies ({@code SyncReadiness#READY}) can honestly
 * report applied player data. {@code DEGRADED} means "waits succeed but nothing was
 * confirmed". {@code FAILED} means the strategy has hard-failed or the plugin is
 * gone; every wait will fail.
 */
public enum SyncReadiness {

    /** The strategy is armed and can observe a real completion signal. */
    READY,

    /** No usable sync signal; waits complete on a fixed delay and stay unconfirmed. */
    DEGRADED,

    /** The strategy cannot make progress; waits fail loudly. */
    FAILED
}

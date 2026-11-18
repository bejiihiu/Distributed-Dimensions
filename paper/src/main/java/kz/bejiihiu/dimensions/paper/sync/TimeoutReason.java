package kz.bejiihiu.dimensions.paper.sync;

/** Why a pending wait did not complete with {@link SyncReady}. */
public enum TimeoutReason {

    /** The wait expired while the player was still online and no completion ever arrived. */
    WAIT_TIMEOUT,

    /** The player left the server before the wait completed (or was offline when it fired). */
    PLAYER_OFFLINE,

    /** The integration broke: shut down, or its listener/detection failed. */
    INTEGRATION_ERROR
}

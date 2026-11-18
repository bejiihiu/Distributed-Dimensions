package kz.bejiihiu.dimensions.common.model;

/**
 * Why a transfer (or entity transfer) failed. Coarse on purpose — the set is small enough to
 * document in one breath:
 *
 * <ul>
 *   <li>{@link #TIMEOUT} — the operation did not finish within the configured budget.</li>
 *   <li>{@link #BACKEND_DOWN} — the target backend is unreachable (connection refused, no route).</li>
 *   <li>{@link #UNAVAILABLE} — the target exists but cannot serve the request right now (overloaded, shutting down).</li>
 *   <li>{@link #REJECTED} — the target understood the request and refused it (validation, policy, bad state).</li>
 *   <li>{@link #DISCONNECTED} — the player disconnected while the transfer was in flight.</li>
 *   <li>{@link #UNKNOWN} — anything else; the detail string carries the specifics.</li>
 * </ul>
 */
public enum TransferError {
    TIMEOUT("timeout"),
    BACKEND_DOWN("backend_down"),
    UNAVAILABLE("unavailable"),
    REJECTED("rejected"),
    DISCONNECTED("disconnected"),
    UNKNOWN("unknown");

    private final String wireName;

    TransferError(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token used on the wire, in the journal, and in metrics. */
    public String wireName() {
        return wireName;
    }

    public static TransferError fromWireName(String wireName) {
        for (TransferError value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown transfer error token: '" + wireName + "'");
    }
}

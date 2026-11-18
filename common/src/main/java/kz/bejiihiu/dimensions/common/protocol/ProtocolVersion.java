package kz.bejiihiu.dimensions.common.protocol;

/**
 * Protocol version constants. The wire format carries a top-level {@code "v"} integer;
 * senders always emit {@link #CURRENT} and receivers hard-fail on anything else.
 * Bumping this is a breaking change: add a new constant and a migration note, never widen the check.
 */
public final class ProtocolVersion {

    /** The only version this build reads and writes. */
    public static final int CURRENT = 1;

    /** Oldest version a reader could accept — kept for clarity of intent, also 1. */
    public static final int MIN_SUPPORTED = 1;

    private ProtocolVersion() {
    }
}

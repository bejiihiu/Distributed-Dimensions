package kz.bejiihiu.dimensions.common.model;

/**
 * Which axis a portal's plane runs along.
 *
 * <ul>
 *   <li>{@link #X_AXIS} — the frame is wide in X; the plane normal faces ±Z.</li>
 *   <li>{@link #Z_AXIS} — the frame is wide in Z; the plane normal faces ±X.</li>
 * </ul>
 */
public enum PortalOrientation {
    X_AXIS("x_axis"),
    Z_AXIS("z_axis");

    private final String wireName;

    PortalOrientation(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token used on the wire and in the portal index table. */
    public String wireName() {
        return wireName;
    }

    /** Parses a wire token; unknown tokens are an error, never a silent fallback. */
    public static PortalOrientation fromWireName(String wireName) {
        for (PortalOrientation value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown portal orientation token: '" + wireName + "'");
    }
}

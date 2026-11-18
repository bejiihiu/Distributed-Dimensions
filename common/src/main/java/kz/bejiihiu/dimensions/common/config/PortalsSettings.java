package kz.bejiihiu.dimensions.common.config;

/** Portal-related switches, grouped by portal kind. */
public record PortalsSettings(NetherPortalsSettings nether, EndPortalsSettings end) {

    public PortalsSettings {
        if (nether == null || end == null) {
            throw new IllegalArgumentException("portals.{nether,end} must all be configured");
        }
    }
}

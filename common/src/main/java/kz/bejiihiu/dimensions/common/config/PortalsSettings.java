package kz.bejiihiu.dimensions.common.config;

/** Portal-related switches, grouped by portal kind. */
public record PortalsSettings(NetherPortalsSettings nether, EndPortalsSettings end, GatewayPortalsSettings gateway) {

    public PortalsSettings {
        if (nether == null || end == null || gateway == null) {
            throw new IllegalArgumentException("portals.{nether,end,gateway} must all be configured");
        }
    }
}

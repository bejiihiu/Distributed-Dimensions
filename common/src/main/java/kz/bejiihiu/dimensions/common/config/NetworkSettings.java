package kz.bejiihiu.dimensions.common.config;

/**
 * Proxy and transfer topology: how transfers behave and which servers they route to.
 *
 * @param sourceServer this backend's own registered server name in the proxy
 *                     (e.g. the name Velocity knows this server by). Velocity rejects
 *                     a cross-server transfer whose {@code source_server} does not
 *                     match the requesting connection's registered name, so transfer
 *                     routing fails closed while this is blank. Grammar
 *                     {@code [A-Za-z0-9_-]{1,64}}; the value is carried verbatim and
 *                     validated at the resolution site, not here.
 */
public record NetworkSettings(TransferSettings transfer,
                              DimensionsSettings dimensions, String sourceServer) {

    public NetworkSettings {
        if (transfer == null || dimensions == null) {
            throw new IllegalArgumentException("network.{transfer,dimensions} must all be configured");
        }
        if (sourceServer == null) {
            throw new IllegalArgumentException("network.source-server must not be null (blank is allowed and means \"unset\")");
        }
    }
}

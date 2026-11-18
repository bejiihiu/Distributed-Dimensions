package kz.bejiihiu.dimensions.common.config;

/** Proxy and transfer topology: who talks to the proxy and how transfers retry. */
public record NetworkSettings(NetworkProxySettings proxy, TransferSettings transfer, DimensionsSettings dimensions) {

    public NetworkSettings {
        if (proxy == null || transfer == null || dimensions == null) {
            throw new IllegalArgumentException("network.{proxy,transfer,dimensions} must all be configured");
        }
    }
}

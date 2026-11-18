package kz.bejiihiu.dimensions.common.config;

/** Proxy participation. When disabled the backend does not advertise or expect proxy-routed transfers. */
public record NetworkProxySettings(boolean enabled) {
}

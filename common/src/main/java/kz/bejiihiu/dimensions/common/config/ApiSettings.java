package kz.bejiihiu.dimensions.common.config;

/** Public API surface switches. */
public record ApiSettings(boolean enabled, ApiCompatSettings compat) {

    public ApiSettings {
        if (compat == null) {
            throw new IllegalArgumentException("api.compat must be configured");
        }
    }
}

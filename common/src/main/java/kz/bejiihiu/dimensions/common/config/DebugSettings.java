package kz.bejiihiu.dimensions.common.config;

/** Verbose logging switches for individual subsystems. Keep false in production. */
public record DebugSettings(
        boolean portalSearch,
        boolean coordinateConversion,
        boolean transfers,
        boolean entityTransfer) {
}

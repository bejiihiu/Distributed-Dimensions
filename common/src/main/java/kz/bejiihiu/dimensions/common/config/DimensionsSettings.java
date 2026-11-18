package kz.bejiihiu.dimensions.common.config;

/** Routing targets for the three well-known dimensions. */
public record DimensionsSettings(DimensionTarget overworld, DimensionTarget nether, DimensionTarget end) {

    public DimensionsSettings {
        if (overworld == null || nether == null || end == null) {
            throw new IllegalArgumentException("dimensions.{overworld,nether,end} must all be configured");
        }
    }
}

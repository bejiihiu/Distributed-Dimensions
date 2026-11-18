package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.DistributedDimensionsAPI;
import kz.bejiihiu.dimensions.api.DimensionRegistry;
import kz.bejiihiu.dimensions.api.PortalRegistry;
import kz.bejiihiu.dimensions.api.TransferManager;

import org.jetbrains.annotations.NotNull;

/**
 * The concrete {@link DistributedDimensionsAPI} handed out by this backend.
 * Stateless views over the live services; nothing here is mutable after construction.
 */
final class PaperDistributedDimensionsAPI extends DistributedDimensionsAPI {

    private final TransferManager transfers;
    private final DimensionRegistry dimensions;
    private final PortalRegistry portals;

    PaperDistributedDimensionsAPI(@NotNull TransferManager transfers, @NotNull DimensionRegistry dimensions,
                                  @NotNull PortalRegistry portals) {
        this.transfers = transfers;
        this.dimensions = dimensions;
        this.portals = portals;
    }

    @Override
    public @NotNull TransferManager transfers() {
        return transfers;
    }

    @Override
    public @NotNull DimensionRegistry dimensions() {
        return dimensions;
    }

    @Override
    public @NotNull PortalRegistry portals() {
        return portals;
    }
}

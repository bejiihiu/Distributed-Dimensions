package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;

/**
 * Conversions between the internal (common) model and the published api module.
 * Tokens are identical on both sides; this is a type translation, not a semantic mapping.
 * Public so the sibling compat package can reuse it; not part of any published contract.
 */
public final class InteropMapping {

    private InteropMapping() {
    }

    /** common → api {@code DimensionId} (key preserved verbatim). */
    public static kz.bejiihiu.dimensions.api.DimensionId apiDimension(DimensionId common) {
        return kz.bejiihiu.dimensions.api.DimensionId.of(common.key());
    }

    /** api → common {@code DimensionId} (key preserved verbatim). */
    public static DimensionId commonDimension(kz.bejiihiu.dimensions.api.DimensionId api) {
        return DimensionId.of(api.key());
    }

    public static kz.bejiihiu.dimensions.api.TransferCause apiCause(TransferCause common) {
        return kz.bejiihiu.dimensions.api.TransferCause.fromWireName(common.wireName());
    }

    public static kz.bejiihiu.dimensions.api.OperationState apiOperationState(OperationState common) {
        return kz.bejiihiu.dimensions.api.OperationState.fromWireName(common.wireName());
    }

    public static kz.bejiihiu.dimensions.api.TransferError apiTransferError(TransferError common) {
        return kz.bejiihiu.dimensions.api.TransferError.fromWireName(common.wireName());
    }
}

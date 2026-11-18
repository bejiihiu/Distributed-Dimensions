package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.paper.transfer.PositionValidator;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Cause-aware arrival position veto: plain nether-portal arrivals are never rejected (they
 * are not respawn points and must not be redirected to world spawn because of a stale bed
 * record elsewhere); everything else delegates to the wrapped validator (the End's
 * bed/anchor check).
 */
public final class NetherCausePositionValidator implements PositionValidator {

    private final PositionValidator delegate;

    public NetherCausePositionValidator(PositionValidator delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean isValid(World world, Position position, UUID playerUuid) {
        return delegate.isValid(world, position, playerUuid);
    }

    @Override
    public boolean isValidFor(World world, Position position, UUID playerUuid,
                              @Nullable TransferOperation operation) {
        if (operation != null && operation.cause() == TransferCause.NETHER_PORTAL) {
            return true;
        }
        return delegate.isValidFor(world, position, playerUuid, operation);
    }
}

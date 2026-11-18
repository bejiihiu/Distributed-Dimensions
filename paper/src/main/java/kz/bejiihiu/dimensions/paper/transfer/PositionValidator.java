package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferOperation;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Optional veto for stored arrival positions, evaluated on the target's region thread
 * (entity scheduler context) after join. Used to detect a recorded bed/anchor that no
 * longer exists (or is uncharged) and fall back to world spawn — vanilla "missing
 * respawn block" semantics.
 *
 * <p>Validators may consult the shared respawn store keyed by {@code playerUuid}; a
 * stored position with no respawn record is a plain portal target and is never vetoed
 * by the End's validator.
 */
public interface PositionValidator {

    boolean isValid(World world, Position position, UUID playerUuid);

    /**
     * Operation-aware veto. Defaults to {@link #isValid(World, Position, UUID)}; a validator
     * that cares about the transfer cause (e.g. "plain portal arrivals always pass") overrides
     * this instead of the bare check. Called with the claimed operation; {@code null} only in
     * tests that never resolve a world.
     */
    default boolean isValidFor(World world, Position position, UUID playerUuid, @Nullable TransferOperation operation) {
        return isValid(world, position, playerUuid);
    }

    /** The no-op validator used for journal-position arrivals by default. */
    PositionValidator ALWAYS = (world, position, playerUuid) -> true;
}

package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.Position;

import org.bukkit.World;

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

    /** The no-op validator used for journal-position arrivals by default. */
    PositionValidator ALWAYS = (world, position, playerUuid) -> true;
}

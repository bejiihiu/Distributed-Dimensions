package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlan;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlanResolver;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;

import java.util.Optional;

/**
 * Arrival plan for the End backend. Two regimes:
 *
 * <ul>
 *   <li><b>End entry portal</b> (cause {@code END_PORTAL}, source dimension overworld or
 *       any non-End dimension): the obsidian-platform coordinates from the journal row —
 *       the source computed them, there is nothing to reconsider.</li>
 *   <li><b>End exit portal</b> (cause {@code END_PORTAL}, source dimension
 *       {@code minecraft:the_end}): the recorded respawn point of the player — but only
 *       when the record's dimension matches the operation's target dimension (a stale or
 *       unroutable record must not teleport the player into a void; the vanilla semantic
 *       is "no usable home -&gt; world spawn"). Otherwise, the backend's world spawn.</li>
 * </ul>
 */
public final class EndArrivalPlanResolver implements ArrivalPlanResolver {

    private final TransferBackend backend;

    public EndArrivalPlanResolver(TransferBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    @Override
    public ArrivalPlan resolve(TransferOperation claimed) {
        if (claimed.cause() == TransferCause.END_PORTAL && claimed.sourceDimension().isTheEnd()) {
            Optional<RespawnPointRecord> record;
            try {
                record = backend.respawnPoints().find(claimed.playerUuid());
            } catch (RuntimeException e) {
                backend.diagnostics().accept("respawn store unavailable for exit resolution of operation "
                        + claimed.operationId() + "; falling back to world spawn");
                return new ArrivalPlan.WorldSpawn();
            }
            if (record.isPresent()
                    && record.get().dimension().equals(claimed.targetDimension())) {
                RespawnPointRecord respawn = record.get();
                return new ArrivalPlan.Stored(respawn.world(), respawn.position());
            }
            return new ArrivalPlan.WorldSpawn();
        }
        return new ArrivalPlan.Stored(null, claimed.targetPosition());
    }
}

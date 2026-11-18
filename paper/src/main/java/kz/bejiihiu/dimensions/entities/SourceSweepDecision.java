package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;

import java.util.List;

/**
 * The pure decision the source sweep makes for one tracked operation, derived only from
 * the rows currently in the store and the age of the tracking entry. Kept Bukkit-free so
 * the anti-duplication rules are unit-testable in isolation.
 *
 * <ul>
 *   <li>{@link Action#RETIRE} — no rows remain for the op: the target spawned everything
 *       and consumed its rows. The source copies are retired.</li>
 *   <li>{@link Action#RETRACT} — at least one row is {@code FAILED}: the target may have
 *       spawned a prefix of the graph before failing, so keeping the source copies risks a
 *       visible duplicate. The source copies are retired as well; total loss is the
 *       documented, accepted cost of the no-dupe rule.</li>
 *   <li>{@link Action#EXPIRE} — the entry outlived the acknowledgement timeout while rows
 *       are still {@code PENDING}/{@code CLAIMED}: the source marks the op failed on its
 *       side and <em>keeps</em> its entities — there is no evidence anything spawned on the
 *       target yet, and un-acknowledged entities must never be silently deleted.</li>
 *   <li>{@link Action#WAIT} — otherwise.</li>
 * </ul>
 */
public record SourceSweepDecision(Action action) {

    public enum Action { RETIRE, RETRACT, EXPIRE, WAIT }

    public static SourceSweepDecision decide(List<EntityTransferPayload> rows,
            long trackedAtMillis, long nowMillis, long ackTimeoutMillis) {
        if (rows.isEmpty()) {
            return new SourceSweepDecision(Action.RETIRE);
        }
        for (EntityTransferPayload row : rows) {
            if (row.state() == EntityPayloadState.FAILED) {
                return new SourceSweepDecision(Action.RETRACT);
            }
        }
        if (nowMillis - trackedAtMillis >= ackTimeoutMillis) {
            return new SourceSweepDecision(Action.EXPIRE);
        }
        return new SourceSweepDecision(Action.WAIT);
    }
}

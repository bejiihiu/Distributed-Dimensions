package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source-side bookkeeping for in-flight entity operations. One entry per op holds the
 * retiring half of the ownership contract: rows arrive on the target, the target deletes
 * them after a full spawn, and the source sweep turns "no rows left" into entity removal.
 *
 * <p>The map is bounded ({@value #MAX_ENTRIES}); the oldest entries are retired on their
 * own timeout path instead of growing without limit. Retirement always goes through
 * {@link Schedulers#runOn(Entity, Runnable)} — a null handle simply means the entity is
 * already gone.
 */
public final class EntityTransferTracker {

    /** Entries older than this are failed and kept with a warning (see SourceSweepDecision). */
    public static final long ACK_TIMEOUT_MILLIS = 10 * 60 * 1_000L;
    public static final int MAX_ENTRIES = 1024;

    private final EntityBackend backend;
    private final EntityTransferMetrics metrics;
    private final Map<String, TrackedAssembly> tracked = new ConcurrentHashMap<>();

    public EntityTransferTracker(EntityBackend backend, EntityTransferMetrics metrics) {
        this.backend = backend;
        this.metrics = metrics;
    }

    /** Track the entities of a freshly-submitted operation. Overwrites any prior entry for the op. */
    public void track(String opId, List<Entity> assembly, long nowMillis) {
        if (tracked.size() >= MAX_ENTRIES) {
            // evict the oldest entry — its op is almost certainly ancient; the sweep path for
            // it is the same as for a TTL expiry (fail + keep)
            String oldestKey = tracked.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(e -> e.getValue().trackedAtMillis()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey != null) {
                expireEntry(oldestKey, System.currentTimeMillis());
            }
        }
        List<UUID> uuids = new ArrayList<>(assembly.size());
        for (Entity entity : assembly) {
            uuids.add(entity.getUniqueId());
        }
        List<Entity> handles = List.copyOf(assembly);
        tracked.put(opId, new TrackedAssembly(opId, uuids, handles, nowMillis));
    }

    /** All tracked op ids (diagnostics). */
    public java.util.Set<String> trackedOpIds() {
        return java.util.Collections.unmodifiableSet(tracked.keySet());
    }

    public int trackedCount() {
        return tracked.size();
    }

    /**
     * Evaluate the sweep for one op: retire / retract / expire / wait — per
     * {@link SourceSweepDecision}. Returns the action taken (for metrics/logging).
     */
    public SourceSweepDecision.Action sweepOp(String opId,
            List<kz.bejiihiu.dimensions.common.model.EntityTransferPayload> rows, long nowMillis) {
        TrackedAssembly assembly = tracked.get(opId);
        if (assembly == null) {
            return SourceSweepDecision.Action.WAIT;
        }
        SourceSweepDecision.Action action =
                SourceSweepDecision.decide(rows, assembly.trackedAtMillis(), nowMillis, ACK_TIMEOUT_MILLIS).action();
        switch (action) {
            case RETIRE -> {
                retireAll(assembly);
                tracked.remove(opId);
                metrics.increment(EntityTransferMetrics.OP_COMPLETED);
            }
            case RETRACT -> {
                backend.diagnostics().accept("entity transfer op " + opId
                        + " reached FAILED — removing source-side assembly to avoid duplication");
                retireAll(assembly);
                tracked.remove(opId);
                metrics.increment(EntityTransferMetrics.SOURCE_RETRACTED);
            }
            case EXPIRE -> expireEntry(opId, nowMillis);
            case WAIT -> {
            }
        }
        return action;
    }

    private void expireEntry(String opId, long nowMillis) {
        backend.diagnostics().accept("entity transfer op " + opId
                + " was not acknowledged within " + ACK_TIMEOUT_MILLIS / 1000
                + "s — marking payloads FAILED and keeping the source entities");
        try {
            backend.entityPayloads().failOp(opId, java.time.Instant.ofEpochMilli(nowMillis));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity transfer op " + opId
                    + ": failed to mark payloads FAILED on ack-expiry: " + e.getMessage());
        }
        tracked.remove(opId);
        metrics.increment(EntityTransferMetrics.SOURCE_ACK_EXPIRED);
    }

    private void retireAll(TrackedAssembly assembly) {
        for (Entity entity : assembly.handles()) {
            try {
                if (entity == null) {
                    continue;
                }
                // no isValid() here — the caller may be an async sweep and validity is entity
                // access; the entity-scheduler lambda re-checks it in the right context anyway
                backend.schedulers().runOn(entity, () -> {
                    try {
                        if (entity.isValid()) {
                            entity.remove();
                        }
                    } catch (RuntimeException e) {
                        backend.diagnostics().accept("failed to retire transferred entity "
                                + entity.getUniqueId() + ": " + e.getMessage());
                    }
                });
            } catch (RuntimeException e) {
                backend.diagnostics().accept("failed to schedule retirement of entity "
                        + entity.getUniqueId() + ": " + e.getMessage());
            }
        }
    }
}

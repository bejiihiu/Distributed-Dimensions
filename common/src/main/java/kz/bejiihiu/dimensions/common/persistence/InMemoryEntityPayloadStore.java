package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Process-local {@link EntityPayloadStore} — the implementation used by tests and by
 * embedded deployments that stay on one JVM. Not durable; backends that need the
 * cross-backend guarantee must open the SQL or Mongo engine instead.
 *
 * <p>The claim path follows the same all-or-nothing rule as the durable engines:
 * every pending row of the op is flipped (or none are), and an inconsistent op has
 * its claimed rows moved to {@code FAILED}.
 */
public final class InMemoryEntityPayloadStore implements EntityPayloadStore {

    private final Map<String, EntityTransferPayload> rows = new ConcurrentHashMap<>();

    private static String key(String opId, int seq) {
        return opId + ":" + seq;
    }

    @Override
    public void insertAll(List<EntityTransferPayload> newRows) {
        if (newRows.isEmpty()) {
            return;
        }
        // reject the batch up front on any duplicate, so a re-sent operation is
        // idempotent instead of half-written
        for (EntityTransferPayload row : newRows) {
            if (rows.containsKey(key(row.opId(), row.seq()))) {
                throw new PersistenceException(
                        "duplicate payload row for op " + row.opId() + " seq " + row.seq(), null);
            }
        }
        for (EntityTransferPayload row : newRows) {
            rows.put(key(row.opId(), row.seq()), row);
        }
    }

    @Override
    public List<EntityTransferPayload> findByOpId(String opId) {
        return rows.values().stream()
                .filter(row -> row.opId().equals(opId))
                .sorted(Comparator.comparingInt(EntityTransferPayload::seq))
                .toList();
    }

    @Override
    public int countForOp(String opId) {
        return (int) rows.values().stream().filter(row -> row.opId().equals(opId)).count();
    }

    @Override
    public List<String> findPendingOpIds(String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return rows.values().stream()
                .filter(row -> row.state() == EntityPayloadState.PENDING && row.targetServer().equals(targetServer))
                .collect(Collectors.groupingBy(EntityTransferPayload::opId,
                        Collectors.minBy(Comparator.comparing(EntityTransferPayload::createdAt))))
                .entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().orElseThrow().createdAt()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<String> findPendingOpIdsForPlayer(UUID playerUuid, String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        rows.values().stream()
                .filter(row -> row.state() == EntityPayloadState.PENDING
                        && row.targetServer().equals(targetServer)
                        && playerUuid.equals(row.playerUuid()))
                .sorted(Comparator.comparing(EntityTransferPayload::createdAt))
                .forEach(row -> {
                    if (ids.size() < limit && !ids.contains(row.opId())) {
                        ids.add(row.opId());
                    }
                });
        return List.copyOf(ids);
    }

    @Override
    public synchronized List<EntityTransferPayload> claimOp(String opId, String targetServer, Instant now) {
        List<EntityTransferPayload> opRows = findByOpId(opId);
        int total = opRows.size();
        if (total == 0) {
            return List.of();
        }
        List<EntityTransferPayload> pendingForUs = opRows.stream()
                .filter(row -> row.state() == EntityPayloadState.PENDING
                        && row.targetServer().equals(targetServer))
                .toList();
        if (pendingForUs.size() != total) {
            // nothing (or less than everything) is pending for us — fail only what we flipped
            if (pendingForUs.isEmpty()) {
                return List.of();
            }
            for (EntityTransferPayload row : pendingForUs) {
                rows.put(key(row.opId(), row.seq()), row.movedTo(EntityPayloadState.FAILED, now));
            }
            return List.of();
        }
        List<EntityTransferPayload> claimed = new ArrayList<>(total);
        for (EntityTransferPayload row : opRows) {
            EntityTransferPayload flipped = row.movedTo(EntityPayloadState.CLAIMED, now);
            rows.put(key(row.opId(), row.seq()), flipped);
            claimed.add(flipped);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized int deleteForOp(String opId) {
        int before = rows.size();
        rows.entrySet().removeIf(entry -> entry.getValue().opId().equals(opId));
        return before - rows.size();
    }

    @Override
    public synchronized int failOp(String opId, Instant now) {
        int flipped = 0;
        for (Map.Entry<String, EntityTransferPayload> entry : rows.entrySet()) {
            EntityTransferPayload row = entry.getValue();
            if (row.opId().equals(opId)
                    && (row.state() == EntityPayloadState.PENDING || row.state() == EntityPayloadState.CLAIMED)) {
                entry.setValue(row.movedTo(EntityPayloadState.FAILED, now));
                flipped++;
            }
        }
        return flipped;
    }

    @Override
    public synchronized int expireStaleClaimed(Instant claimedBefore, Instant now) {
        int flipped = 0;
        for (Map.Entry<String, EntityTransferPayload> entry : rows.entrySet()) {
            EntityTransferPayload row = entry.getValue();
            if (row.state() == EntityPayloadState.CLAIMED
                    && row.updatedAt().isBefore(claimedBefore)) {
                entry.setValue(row.movedTo(EntityPayloadState.FAILED, now));
                flipped++;
            }
        }
        return flipped;
    }

    @Override
    public synchronized int deleteFailedOlderThan(Instant cutoff) {
        int before = rows.size();
        rows.entrySet().removeIf(entry -> entry.getValue().state() == EntityPayloadState.FAILED
                && entry.getValue().createdAt().isBefore(cutoff));
        return before - rows.size();
    }

    /** Test hook: total row count across all operations. */
    int size() {
        return rows.size();
    }
}

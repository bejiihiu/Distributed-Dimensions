package kz.bejiihiu.dimensions.entities;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Atomic counters for the entity-transfer subsystem. Diagnostic-grade only — nothing here is
 * wired to a metrics exporter yet; the SEVERE/WARN log lines are the primary signal. Exposed
 * via {@link #snapshot()} for admin-tooling use.
 */
public final class EntityTransferMetrics {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void add(String name, long amount) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(amount);
    }

    /** Snapshot of all counters since start (alphabetical order). */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((name, adder) -> out.put(name, adder.longValue()));
        return Collections.unmodifiableMap(out);
    }

    // Named counters, kept as constants to avoid typos across call sites
    public static final String OP_STARTED = "entity_transfer_started_total";
    public static final String OP_REFUSED = "entity_transfer_refused_total";
    public static final String OP_COMPLETED = "entity_transfer_completed_total";
    public static final String OP_FAILED = "entity_transfer_failed_total";
    public static final String NODE_SPAWNED = "entity_transfer_spawned_total";
    public static final String DEGRADED = "entity_transfer_degraded_total";
    public static final String COOLDOWN_BLOCKED = "entity_transfer_cooldown_blocked_total";
    public static final String LEASHED = "entity_transfer_leashed_skipped_total";
    public static final String PLAYER_DROPPED = "entity_transfer_player_dropped_total";
    public static final String PORTAL_CREATED = "entity_transfer_portal_created_total";
    public static final String PORTAL_FIND_FAILED = "entity_transfer_portal_find_failed_total";
    public static final String PORTAL_CREATE_FAILED = "entity_transfer_portal_create_failed_total";
    public static final String SOURCE_ACK_EXPIRED = "entity_transfer_source_ack_expired_total";
    public static final String SOURCE_RETRACTED = "entity_transfer_source_retracted_total";
    public static final String SPAWN_FAILED = "entity_transfer_spawn_failed_total";
    public static final String CAT_DISABLED = "entity_transfer_category_disabled_total";
}

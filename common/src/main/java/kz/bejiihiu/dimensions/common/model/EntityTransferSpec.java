package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One entity in a transfer assembly, recursive by passenger order.
 *
 * <p>Passengers are listed in mounting order — index 0 rides first, the same order vanilla
 * restores them in. The whole assembly travels in a single operation; if any part fails the
 * operation fails and the source assembly stays put (anti-duplication invariant).
 *
 * <p>{@code entityTypeKey} is a resource key ({@code minecraft:minecart}). Non-vanilla entity
 * types from other plugins are carried opaquely — we never inspect them, only transport.
 *
 * <p>Recursion depth is capped at {@value #MAX_PASSENGER_DEPTH}: a truck of invalid data must
 * not turn the decoder into a stack-overflow machine.
 */
public record EntityTransferSpec(
        UUID entityUuid,
        String entityTypeKey,
        Position position,
        Velocity velocity,
        List<EntityTransferSpec> passengers,
        @Nullable EntityPortalCooldownHint cooldownHint) {

    public static final int MAX_PASSENGER_DEPTH = 16;

    private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public EntityTransferSpec {
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        entityTypeKey = requireResourceKey(entityTypeKey);
        Objects.requireNonNull(passengers, "passengers");
        passengers = List.copyOf(passengers);
        if (passengers.size() > 64) {
            throw new IllegalArgumentException("an entity assembly may carry at most 64 direct passengers");
        }
        int depth = passengerDepth(passengers);
        if (depth >= MAX_PASSENGER_DEPTH) {
            throw new IllegalArgumentException("passenger assembly exceeds max depth of " + MAX_PASSENGER_DEPTH);
        }
    }

    private static int passengerDepth(List<EntityTransferSpec> passengers) {
        int depth = 0;
        for (EntityTransferSpec passenger : passengers) {
            depth = Math.max(depth, 1 + passengerDepth(passenger.passengers()));
        }
        return depth;
    }

    private static String requireResourceKey(String key) {
        Objects.requireNonNull(key, "entityTypeKey");
        String normalized = key.strip().toLowerCase(java.util.Locale.ROOT);
        if (!RESOURCE_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("not a valid entity resource key: '" + key + "'");
        }
        return normalized;
    }
}

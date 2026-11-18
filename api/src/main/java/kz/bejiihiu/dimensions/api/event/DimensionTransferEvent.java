package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.TransferCause;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Base type of all dimension transfer events. Carries the fields shared by every stage of a
 * transfer; concrete subclasses document which backend fires them and under which conditions.
 *
 * <p>A transfer may be a player transfer or an entity transfer. {@link #getEntity()} always
 * returns the transferring entity; for player transfers the entity <em>is</em> the player, and
 * {@link #getPlayer()} additionally returns it as a {@link Player}. For non-player transfers
 * {@link #getPlayer()} returns {@code null}.
 *
 * <p>Entity references handed out by these events follow normal Bukkit validity rules: after a
 * player disconnects, methods on the reference may throw or return stale data. On
 * {@code DimensionTransferFailedEvent}, check {@code playerOnline()} before touching the
 * reference.
 *
 * <p>All events of this family are dispatched synchronously by the Paper plugin manager on the
 * backend named by the subclass. On Folia, portal-driven transfers dispatch on the region thread
 * of the triggering block; entity-driven transfers dispatch on the entity's owning thread;
 * respawn-driven transfers dispatch in the respawn handling context. Listeners that need to
 * touch world state must use the appropriate region/entity scheduler for the target objects.
 *
 * <p>Handler lists are per concrete event type, following the Bukkit convention; listen to the
 * concrete event class, not to this base class (the base class declares no handler list either
 * way, mirroring how plain Bukkit base events such as {@code PlayerEvent} behave).
 */
public abstract class DimensionTransferEvent extends Event {

    private final UUID operationId;
    private final Entity entity;
    private final DimensionId from;
    private final DimensionId to;
    private final TransferCause cause;

    protected DimensionTransferEvent(@NotNull UUID operationId, @NotNull Entity entity,
                                     @NotNull DimensionId from, @NotNull DimensionId to,
                                     @NotNull TransferCause cause) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    /**
     * The id of the transfer operation this event belongs to. The same id appears in
     * {@code DimensionTransferStartEvent}, {@code DimensionTransferCompleteEvent} /
     * {@code DimensionTransferFailedEvent}, and in {@code TransferManager#status(UUID)}.
     */
    @NotNull
    public final UUID getOperationId() {
        return operationId;
    }

    /**
     * The transferring entity. For player transfers this is the player; for entity transfers
     * this is the transferred entity (or, on failure, the source entity which was not removed).
     */
    @NotNull
    public final Entity getEntity() {
        return entity;
    }

    /**
     * The transferring player, or {@code null} if this is a non-player entity transfer.
     */
    @Nullable
    public final Player getPlayer() {
        return entity instanceof Player player ? player : null;
    }

    /** Whether this event describes a player transfer. */
    public final boolean isPlayerTransfer() {
        return entity instanceof Player;
    }

    /** The dimension the entity is leaving. */
    @NotNull
    public final DimensionId getFrom() {
        return from;
    }

    /** The dimension the entity is heading to. */
    @NotNull
    public final DimensionId getTo() {
        return to;
    }

    /** What triggered the transfer. */
    @NotNull
    public final TransferCause getCause() {
        return cause;
    }
}

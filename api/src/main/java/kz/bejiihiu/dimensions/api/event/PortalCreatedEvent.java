package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the <strong>target</strong> backend when the portal resolution step of a transfer
 * created a new portal for the arriving player/entity (none was found within the search radius).
 *
 * <p><strong>Unstable — internal detail, do not listen.</strong> This event exists so the
 * plugin's own compat layer and diagnostics can observe portal creation. Its payload, timing,
 * and even its existence may change in any minor release. Third-party plugins that need portal
 * behaviour should listen to the transfer events ({@code DimensionTransferStartEvent} /
 * {@code DimensionTransferCompleteEvent}) or use {@code PlayerPortalEvent} / configuration
 * instead. It carries no cancellation or veto power and is not ordered against other plugin
 * events.
 *
 * <p>{@link #isForced()} reports the forced-portal fallback: when no suitable location could be
 * found, the implementation drops an obsidian platform inside the world-height budget and
 * records the portal there, overwriting blocks in its path.
 *
 * <p>Thread context: synchronous, on the thread handling the transfer arrival on the target
 * backend.
 */
public class PortalCreatedEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID operationId;
    private final Entity entity;
    private final DimensionId targetDimension;
    private final Location portalLocation;
    private final boolean forced;

    public PortalCreatedEvent(@NotNull UUID operationId, @NotNull Entity entity,
                              @NotNull DimensionId targetDimension, @NotNull Location portalLocation,
                              boolean forced) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.targetDimension = Objects.requireNonNull(targetDimension, "targetDimension");
        this.portalLocation = Objects.requireNonNull(portalLocation, "portalLocation").clone();
        this.forced = forced;
    }

    /** The id of the transfer operation this portal creation belongs to. */
    @NotNull
    public UUID getOperationId() {
        return operationId;
    }

    /** The arriving player or entity. */
    @NotNull
    public Entity getEntity() {
        return entity;
    }

    /** The arriving player, or {@code null} for a non-player entity. */
    @Nullable
    public Player getPlayer() {
        return entity instanceof Player player ? player : null;
    }

    /** Whether the arriving entity is a player. */
    public boolean isPlayerTransfer() {
        return entity instanceof Player;
    }

    /** The dimension the portal was created in — the transfer's destination. */
    @NotNull
    public DimensionId getTargetDimension() {
        return targetDimension;
    }

    /** The exit point the arriving entity will be placed at, cloned on access. */
    @NotNull
    public Location getPortalLocation() {
        return portalLocation.clone();
    }

    /**
     * Whether this portal was placed by the forced-portal fallback (no suitable location found,
     * blocks overwritten) rather than by the regular buildable-area search.
     */
    public boolean isForced() {
        return forced;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    /** The handler list for this event type. Required by the Bukkit event machinery. */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}

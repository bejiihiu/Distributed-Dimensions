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
 * found an existing portal to place the arriving player/entity into.
 *
 * <p><strong>Unstable — internal detail, do not listen.</strong> This event exists so the
 * plugin's own compat layer and diagnostics can observe portal resolution. Its payload, timing,
 * and even its existence may change in any minor release. Third-party plugins that need portal
 * behaviour should listen to the transfer events ({@code DimensionTransferStartEvent} /
 * {@code DimensionTransferCompleteEvent}) or use {@code PlayerPortalEvent} / configuration
 * instead. It carries no cancellation or veto power and is not ordered against other plugin
 * events.
 *
 * <p>Thread context: synchronous, on the thread handling the transfer arrival on the target
 * backend.
 */
public class PortalFoundEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final UUID operationId;
    private final Entity entity;
    private final DimensionId targetDimension;
    private final Location portalLocation;

    public PortalFoundEvent(@NotNull UUID operationId, @NotNull Entity entity,
                            @NotNull DimensionId targetDimension, @NotNull Location portalLocation) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.targetDimension = Objects.requireNonNull(targetDimension, "targetDimension");
        this.portalLocation = Objects.requireNonNull(portalLocation, "portalLocation").clone();
    }

    /** The id of the transfer operation this portal resolution belongs to. */
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

    /** The dimension the portal was searched in — the transfer's destination. */
    @NotNull
    public DimensionId getTargetDimension() {
        return targetDimension;
    }

    /** The exit point the arriving entity will be placed at, cloned on access. */
    @NotNull
    public Location getPortalLocation() {
        return portalLocation.clone();
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

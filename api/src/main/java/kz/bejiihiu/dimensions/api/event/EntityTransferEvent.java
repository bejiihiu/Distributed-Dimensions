package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.TransferCause;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the <strong>target</strong> backend when a non-player entity transfer has been
 * materialized — the snapshot arriving from the source backend was turned into an entity on
 * this backend.
 *
 * <p>This event exists for the entity transfer pipeline (Phase 5). The wiring for non-player
 * entity transfers ships in Phase 5; player transfers never produce it.
 *
 * <p>{@link #getSnapshot()} returns the snapshot the entity was created from. It is exposed as
 * a diagnostic reference — do <em>not</em> call {@code createEntity(...)} on it unless you
 * deliberately want a duplicate entity; the transfer pipeline is responsible for spawning
 * exactly one copy.
 *
 * <p>Ordering on the target backend for an entity transfer:
 * {@code EntityTransferEvent} (entity materialized) → remaining sync/finalization →
 * {@code DimensionTransferCompleteEvent} (operation committed). If materialization itself fails,
 * no {@code EntityTransferEvent} is fired and the operation ends as
 * {@code DimensionTransferFailedEvent}.
 *
 * <p>Not cancellable. Thread context: synchronous on the entity's owning thread of the target
 * backend (Folia).
 */
public class EntityTransferEvent extends DimensionTransferEvent {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final EntitySnapshot snapshot;

    public EntityTransferEvent(@NotNull UUID operationId, @NotNull Entity entity,
                               @NotNull DimensionId from, @NotNull DimensionId to,
                               @NotNull TransferCause cause, @NotNull EntitySnapshot snapshot) {
        super(operationId, entity, from, to, cause);
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * The snapshot the transferred entity was created from.
     *
     * @return the snapshot; never {@code null}
     */
    @NotNull
    public EntitySnapshot getSnapshot() {
        return snapshot;
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

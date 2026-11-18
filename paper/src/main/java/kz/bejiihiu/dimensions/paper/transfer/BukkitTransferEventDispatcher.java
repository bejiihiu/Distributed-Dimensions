package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.OperationState;
import kz.bejiihiu.dimensions.api.TransferCause;
import kz.bejiihiu.dimensions.api.TransferError;
import kz.bejiihiu.dimensions.api.event.DimensionTransferCompleteEvent;
import kz.bejiihiu.dimensions.api.event.DimensionTransferFailedEvent;
import kz.bejiihiu.dimensions.api.event.DimensionTransferStartEvent;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fires the api events through the server's plugin manager. Failures inside listener
 * chains are the listeners' business -- the events follow Bukkit semantics and always
 * complete their dispatch. A {@code null} player reference on {@code fireFailed} drops
 * the event (the api contract requires an entity); the journal is unaffected.
 */
public final class BukkitTransferEventDispatcher implements TransferEventDispatcher {

    private final JavaPlugin plugin;

    public BukkitTransferEventDispatcher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean fireStart(UUID operationId, Player player,
                             kz.bejiihiu.dimensions.common.model.DimensionId from,
                             kz.bejiihiu.dimensions.common.model.DimensionId to,
                             kz.bejiihiu.dimensions.common.model.TransferCause cause) {
        DimensionTransferStartEvent event = new DimensionTransferStartEvent(
                operationId, player, dimOf(from), dimOf(to), causeOf(cause));
        plugin.getServer().getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Override
    public void fireComplete(UUID operationId, Player player,
                             kz.bejiihiu.dimensions.common.model.DimensionId from,
                             kz.bejiihiu.dimensions.common.model.DimensionId to,
                             kz.bejiihiu.dimensions.common.model.TransferCause cause,
                             long durationMillis) {
        DimensionTransferCompleteEvent event = new DimensionTransferCompleteEvent(
                operationId, player, dimOf(from), dimOf(to), causeOf(cause),
                Math.max(0L, durationMillis));
        plugin.getServer().getPluginManager().callEvent(event);
    }

    @Override
    public void fireFailed(UUID operationId, @Nullable Player player,
                           kz.bejiihiu.dimensions.common.model.DimensionId from,
                           kz.bejiihiu.dimensions.common.model.DimensionId to,
                           kz.bejiihiu.dimensions.common.model.TransferCause cause,
                           kz.bejiihiu.dimensions.common.model.TransferError error,
                           kz.bejiihiu.dimensions.common.model.OperationState phase,
                           boolean playerOnline) {
        if (player == null) {
            plugin.getLogger().severe("dimension transfer failed (op " + operationId
                    + "): no player reference available -- DimensionTransferFailedEvent cannot be "
                    + "built (the api contract requires an entity). The journal row is FAILED regardless.");
            return;
        }
        DimensionTransferFailedEvent event = new DimensionTransferFailedEvent(
                operationId, player, dimOf(from), dimOf(to), causeOf(cause),
                errorOf(error), stateOf(phase), playerOnline);
        plugin.getServer().getPluginManager().callEvent(event);
    }

    // ------------------------------------------------------------------ mappings

    static DimensionId dimOf(kz.bejiihiu.dimensions.common.model.DimensionId id) {
        return DimensionId.of(id.key());
    }

    static TransferCause causeOf(kz.bejiihiu.dimensions.common.model.TransferCause cause) {
        return TransferCause.fromWireName(cause.wireName());
    }

    static TransferError errorOf(kz.bejiihiu.dimensions.common.model.TransferError error) {
        return TransferError.fromWireName(error.wireName());
    }

    static OperationState stateOf(kz.bejiihiu.dimensions.common.model.OperationState state) {
        return OperationState.fromWireName(state.wireName());
    }
}

package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.OperationState;
import kz.bejiihiu.dimensions.api.TransferCause;
import kz.bejiihiu.dimensions.api.TransferError;
import kz.bejiihiu.dimensions.api.event.DimensionTransferCompleteEvent;
import kz.bejiihiu.dimensions.api.event.DimensionTransferFailedEvent;
import kz.bejiihiu.dimensions.api.event.DimensionTransferStartEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Single dispatch point for the published transfer events. Feature waves (guards,
 * programmatic API, the arrival pipeline) call these instead of constructing events
 * themselves, so the dispatch path — and its folded-in type translation — lives in
 * exactly one place.
 *
 * <p>Thread context follows the event javadoc: {@code StartEvent} fires on the triggering
 * thread and is cancellable; {@code CompleteEvent}/{@code FailedEvent} are not cancellable
 * and are dispatched wherever the caller decides (this class does not pick the thread).
 *
 * <p>{@code dispatchComplete} exists for the target-side arrival pipeline; this wave never
 * calls it (the arrival pipeline that materializes the player lives in the portal transfer
 * waves), but it is part of the shared seam, not a stub.
 */
public final class TransferEvents {

    private TransferEvents() {
    }

    /**
     * Dispatches {@code DimensionTransferStartEvent}.
     *
     * @return true when a listener cancelled the event (the operation must not start)
     */
    public static boolean dispatchStart(@NotNull UUID operationId, @NotNull Entity entity,
                                        @NotNull DimensionId from, @NotNull DimensionId to,
                                        @NotNull TransferCause cause) {
        PluginManager manager = Bukkit.getPluginManager();
        DimensionTransferStartEvent event =
                new DimensionTransferStartEvent(operationId, entity, from, to, cause);
        manager.callEvent(event);
        return event.isCancelled();
    }

    /** Dispatches {@code DimensionTransferCompleteEvent} on the target backend. */
    public static void dispatchComplete(@NotNull UUID operationId, @NotNull Entity entity,
                                        @NotNull DimensionId from, @NotNull DimensionId to,
                                        @NotNull TransferCause cause, long durationMillis) {
        PluginManager manager = Bukkit.getPluginManager();
        DimensionTransferCompleteEvent event =
                new DimensionTransferCompleteEvent(operationId, entity, from, to, cause, durationMillis);
        manager.callEvent(event);
    }

    /** Dispatches {@code DimensionTransferFailedEvent}. */
    public static void dispatchFailed(@NotNull UUID operationId, @NotNull Entity entity,
                                      @NotNull DimensionId from, @NotNull DimensionId to,
                                      @NotNull TransferCause cause,
                                      @NotNull TransferError error, @NotNull OperationState phase,
                                      boolean playerOnline) {
        PluginManager manager = Bukkit.getPluginManager();
        DimensionTransferFailedEvent event = new DimensionTransferFailedEvent(
                operationId, entity, from, to, cause, error, phase, playerOnline);
        manager.callEvent(event);
    }
}

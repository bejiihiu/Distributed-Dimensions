package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every api event the orchestration dispatches. The start event can be primed to
 * report itself cancelled (the api contract: cancellation vetoes the transfer).
 */
public final class RecordingTransferEventDispatcher implements TransferEventDispatcher {

    public record StartCall(UUID operationId, Player player, DimensionId from, DimensionId to, TransferCause cause) {
    }

    public record CompleteCall(UUID operationId, Player player, DimensionId from, DimensionId to,
                        TransferCause cause, long durationMillis) {
    }

    public record FailedCall(UUID operationId, Player player, DimensionId from, DimensionId to,
                      TransferCause cause, TransferError error, OperationState phase, boolean playerOnline) {
    }

    private final List<StartCall> starts = new CopyOnWriteArrayList<>();
    private final List<CompleteCall> completes = new CopyOnWriteArrayList<>();
    private final List<FailedCall> failures = new CopyOnWriteArrayList<>();
    private volatile boolean cancelStart;

    public void setCancelStart(boolean cancelStart) {
        this.cancelStart = cancelStart;
    }

    public List<StartCall> starts() {
        return List.copyOf(starts);
    }

    public List<CompleteCall> completes() {
        return List.copyOf(completes);
    }

    public List<FailedCall> failures() {
        return List.copyOf(failures);
    }

    @Override
    public boolean fireStart(UUID operationId, Player player,
                             DimensionId from, DimensionId to, TransferCause cause) {
        starts.add(new StartCall(operationId, player, from, to, cause));
        return cancelStart;
    }

    @Override
    public void fireComplete(UUID operationId, Player player,
                             DimensionId from, DimensionId to, TransferCause cause, long durationMillis) {
        completes.add(new CompleteCall(operationId, player, from, to, cause, durationMillis));
    }

    @Override
    public void fireFailed(UUID operationId, Player player,
                           DimensionId from, DimensionId to, TransferCause cause,
                           TransferError error, OperationState phase, boolean playerOnline) {
        failures.add(new FailedCall(operationId, player, from, to, cause, error, phase, playerOnline));
    }
}




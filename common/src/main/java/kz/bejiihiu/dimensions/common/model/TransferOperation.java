package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row in the operation journal — the durable record of a player (or entity) transfer.
 * The journal is the source of truth for "was this operation already handled?", which is what
 * makes retried requests safe.
 *
 * <p>{@code error}/{@code errorMessage} are set only when {@code state} is
 * {@link OperationState#FAILED} — enforced by {@link #withState}, not by the constructor,
 * because reading a row must never fail on legacy data.
 */
public record TransferOperation(
        UUID operationId,
        UUID playerUuid,
        String sourceServer,
        String targetServer,
        DimensionId sourceDimension,
        DimensionId targetDimension,
        TransferCause cause,
        Position targetPosition,
        OperationState state,
        Instant createdAt,
        Instant updatedAt,
        @Nullable TransferError error,
        @Nullable String errorMessage) {

    public TransferOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(sourceServer, "sourceServer");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(sourceDimension, "sourceDimension");
        Objects.requireNonNull(targetDimension, "targetDimension");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(targetPosition, "targetPosition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /** Creates a fresh journal row in {@link OperationState#CREATED} state. */
    public static TransferOperation created(UUID operationId, UUID playerUuid,
                                           String sourceServer, String targetServer,
                                           DimensionId sourceDimension, DimensionId targetDimension,
                                           Position targetPosition, TransferCause cause, Instant at) {
        return new TransferOperation(operationId, playerUuid, sourceServer, targetServer,
                sourceDimension, targetDimension, cause, targetPosition,
                OperationState.CREATED, at, at, null, null);
    }

    /**
     * Same row advanced to {@code newState}. Enforces the state machine — callers never bypass
     * it. The error must be supplied exactly when transitioning to {@link OperationState#FAILED}.
     */
    public TransferOperation withState(OperationState newState, Instant at,
                                       @Nullable TransferError error, @Nullable String errorMessage) {
        state.requireTransitionTo(newState);
        if (newState == OperationState.FAILED) {
            Objects.requireNonNull(error, "a transition to FAILED must carry a TransferError");
        } else if (error != null) {
            throw new IllegalArgumentException("non-FAILED state must not carry an error code");
        }
        return new TransferOperation(operationId, playerUuid, sourceServer, targetServer,
                sourceDimension, targetDimension, cause, targetPosition, newState,
                createdAt, at, error, errorMessage);
    }
}

package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferOperationTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_000_000);
    private static final Instant T1 = T0.plusMillis(1000);
    private static final Instant T2 = T1.plusMillis(1000);

    private static TransferOperation freshOperation() {
        return TransferOperation.created(
                UUID.randomUUID(), UUID.randomUUID(),
                "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(1, 64, 2), TransferCause.NETHER_PORTAL, T0);
    }

    private static void assertCanFail(TransferOperation op) {
        TransferOperation failed = op.withState(OperationState.FAILED, T2, TransferError.TIMEOUT, "boom");
        assertEquals(OperationState.FAILED, failed.state());
        assertEquals(TransferError.TIMEOUT, failed.error());
        assertEquals("boom", failed.errorMessage());
    }

    @Test
    void happyPathWalksCreatedToCommitted() {
        TransferOperation op = freshOperation();
        assertEquals(OperationState.CREATED, op.state());

        op = op.withState(OperationState.PREPARING, T1, null, null);
        op = op.withState(OperationState.TARGET_CREATED, T1, null, null);
        op = op.withState(OperationState.SOURCE_REMOVED, T1, null, null);
        op = op.withState(OperationState.COMMITTED, T2, null, null);

        assertEquals(OperationState.COMMITTED, op.state());
        assertEquals(T0, op.createdAt());
        assertEquals(T2, op.updatedAt());
        assertTrue(op.state().isTerminal());
    }

    @Test
    void failureIsAllowedFromEveryNonTerminalState() {
        TransferOperation op = freshOperation();
        assertCanFail(op); // CREATED
        op = op.withState(OperationState.PREPARING, T1, null, null);
        assertCanFail(op); // PREPARING
        op = op.withState(OperationState.TARGET_CREATED, T1, null, null);
        assertCanFail(op); // TARGET_CREATED
        op = op.withState(OperationState.SOURCE_REMOVED, T1, null, null);
        assertCanFail(op); // SOURCE_REMOVED
    }

    @Test
    void createdAllowsExactlyPreparingAndFailed() {
        assertTrue(OperationState.CREATED.canTransitionTo(OperationState.PREPARING));
        assertTrue(OperationState.CREATED.canTransitionTo(OperationState.FAILED));
        assertEquals(2, java.util.Arrays.stream(OperationState.values())
                .filter(OperationState.CREATED::canTransitionTo).count());
        assertTrue(OperationState.COMMITTED.isTerminal());
        assertTrue(OperationState.FAILED.isTerminal());
    }

    @Test
    void illegalTransitionsThrow() {
        TransferOperation op = freshOperation();
        // skipping ahead
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> op.withState(OperationState.COMMITTED, T1, null, null));
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> op.withState(OperationState.SOURCE_REMOVED, T1, null, null));
        // sideways to a state one step ahead of us only
        TransferOperation preparing = op.withState(OperationState.PREPARING, T1, null, null);
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> preparing.withState(OperationState.COMMITTED, T1, null, null));
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> preparing.withState(OperationState.CREATED, T1, null, null));

        TransferOperation committed = preparing
                .withState(OperationState.TARGET_CREATED, T1, null, null)
                .withState(OperationState.SOURCE_REMOVED, T1, null, null)
                .withState(OperationState.COMMITTED, T2, null, null);
        // terminal states reject everything, even themselves
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> committed.withState(OperationState.FAILED, T2, TransferError.UNKNOWN, null));
        assertThrows(IllegalOperationStateTransitionException.class,
                () -> committed.withState(OperationState.COMMITTED, T2, null, null));
    }

    @Test
    void failedRequiresErrorCode() {
        assertThrows(NullPointerException.class, () -> freshOperation()
                .withState(OperationState.FAILED, T1, null, "message without error code"));
    }

    @Test
    void nonFailedStatesRejectErrorCodes() {
        assertThrows(IllegalArgumentException.class, () -> freshOperation()
                .withState(OperationState.PREPARING, T1, TransferError.UNKNOWN, "should not happen"));
    }

    @Test
    void stateWireNamesRoundTrip() {
        for (OperationState state : OperationState.values()) {
            assertEquals(state, OperationState.fromWireName(state.wireName()));
        }
        assertEquals("target_created", OperationState.TARGET_CREATED.wireName());
        assertEquals("source_removed", OperationState.SOURCE_REMOVED.wireName());
    }

    @Test
    void transitionExceptionCarriesEndpoints() {
        IllegalOperationStateTransitionException e = assertThrows(
                IllegalOperationStateTransitionException.class,
                () -> OperationState.CREATED.requireTransitionTo(OperationState.COMMITTED));
        assertEquals(OperationState.CREATED, e.from());
        assertEquals(OperationState.COMMITTED, e.to());
        assertTrue(e.getMessage().contains("CREATED -> COMMITTED"));
    }

    @Test
    void cannotFabricateUpdatedBeforeCreated() {
        Instant before = T0.minusMillis(1);
        assertThrows(IllegalArgumentException.class, () -> new TransferOperation(
                UUID.randomUUID(), UUID.randomUUID(), "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                TransferCause.PROGRAMMATIC,
                new Position(0, 64, 0),
                OperationState.CREATED, T0, before, null, null));
    }
}

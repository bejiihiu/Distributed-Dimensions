package kz.bejiihiu.dimensions.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationStateTest {

    @Test
    void happyPathTransitionsAreAllowedInOrder() {
        OperationState[] path = {OperationState.CREATED, OperationState.PREPARING,
                OperationState.TARGET_CREATED, OperationState.SOURCE_REMOVED,
                OperationState.COMMITTED};
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(path[i].canTransitionTo(path[i + 1]),
                    path[i] + " -> " + path[i + 1] + " must be allowed");
        }
    }

    @Test
    void everyNonTerminalStateMayFail() {
        for (OperationState state : OperationState.values()) {
            if (state == OperationState.COMMITTED || state == OperationState.FAILED) {
                continue;
            }
            assertTrue(state.canTransitionTo(OperationState.FAILED),
                    state + " -> FAILED must be allowed");
        }
    }

    @Test
    void terminalStatesAcceptNothing() {
        for (OperationState next : OperationState.values()) {
            assertFalse(OperationState.COMMITTED.canTransitionTo(next));
            assertFalse(OperationState.FAILED.canTransitionTo(next));
        }
    }

    @Test
    void skippingStepsIsRejected() {
        assertFalse(OperationState.CREATED.canTransitionTo(OperationState.COMMITTED));
        assertFalse(OperationState.CREATED.canTransitionTo(OperationState.TARGET_CREATED));
        assertFalse(OperationState.PREPARING.canTransitionTo(OperationState.COMMITTED));
        assertFalse(OperationState.TARGET_CREATED.canTransitionTo(OperationState.COMMITTED));
        // terminal to in-flight is nonsense too
        assertFalse(OperationState.COMMITTED.canTransitionTo(OperationState.CREATED));
    }

    @Test
    void isTerminalOnlyForCommittedAndFailed() {
        assertTrue(OperationState.COMMITTED.isTerminal());
        assertTrue(OperationState.FAILED.isTerminal());
        Set<OperationState> open = Set.of(OperationState.CREATED, OperationState.PREPARING,
                OperationState.TARGET_CREATED, OperationState.SOURCE_REMOVED);
        for (OperationState state : open) {
            assertFalse(state.isTerminal());
        }
    }

    @Test
    void wireNameRoundTrips() {
        for (OperationState state : OperationState.values()) {
            assertEquals(state, OperationState.fromWireName(state.wireName()));
        }
    }

    @Test
    void fromWireNameRejectsUnknownTokens() {
        assertThrows(IllegalArgumentException.class, () -> OperationState.fromWireName("nope"));
        assertThrows(IllegalArgumentException.class, () -> OperationState.fromWireName("CREATED"));
    }

    @Test
    void wireNamesAreStableLowercaseSnake() {
        // the journal stores these; renaming one is a breaking change, so pin them down
        assertEquals("created", OperationState.CREATED.wireName());
        assertEquals("target_created", OperationState.TARGET_CREATED.wireName());
        assertEquals("source_removed", OperationState.SOURCE_REMOVED.wireName());
        assertEquals("committed", OperationState.COMMITTED.wireName());
        assertEquals("failed", OperationState.FAILED.wireName());
    }
}

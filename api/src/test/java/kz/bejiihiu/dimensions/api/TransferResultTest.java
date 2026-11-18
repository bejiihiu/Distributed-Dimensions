package kz.bejiihiu.dimensions.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferResultTest {

    private static final UUID OP = UUID.randomUUID();

    @Test
    void successCarriesNoError() {
        TransferResult result = TransferResult.success(OP);
        assertTrue(result.ok());
        assertEquals(OP, result.operationId());
        assertNull(result.error());
        assertNull(result.message());
    }

    @Test
    void failureRequiresErrorAndCarriesMessage() {
        TransferResult result = TransferResult.failure(OP, TransferError.TIMEOUT, "budget exceeded");
        assertFalse(result.ok());
        assertEquals(TransferError.TIMEOUT, result.error());
        assertEquals("budget exceeded", result.message());
    }

    @Test
    void successfulResultMustNotCarryError() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransferResult(true, OP, TransferError.UNKNOWN, null));
    }

    @Test
    void failedResultMustCarryError() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransferResult(false, OP, null, null));
    }

    @Test
    void factoriesRejectNullError() {
        assertThrows(NullPointerException.class, () -> TransferResult.failure(OP, null));
        assertThrows(NullPointerException.class, () -> TransferResult.failure(OP, null, "msg"));
    }

    @Test
    void operationIdIsMandatory() {
        assertThrows(NullPointerException.class,
                () -> new TransferResult(true, null, null, null));
    }
}

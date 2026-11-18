package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRequestTest {

    private static TransferRequest valid() {
        return new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(),
                "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(1, 64, 2), TransferCause.NETHER_PORTAL);
    }

    @Test
    void validRequestConstructs() {
        TransferRequest request = valid();
        assertEquals("overworld", request.sourceServer());
        assertEquals(TransferCause.NETHER_PORTAL, request.cause());
    }

    @Test
    void serverNamesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), "bad server!", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.PROGRAMMATIC));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), "x".repeat(65), "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.PROGRAMMATIC));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), "", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.PROGRAMMATIC));
    }

    @Test
    void sameServerIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), "Overworld", "overworld",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.PROGRAMMATIC));
    }

    @Test
    void sameDimensionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), "overworld", "nether",
                DimensionId.NETHER, DimensionId.NETHER,
                new Position(0, 64, 0), TransferCause.PROGRAMMATIC));
    }

    @Test
    void resultInvariantsAreEnforced() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new TransferResult(true, id, null, TransferError.TIMEOUT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferResult(false, id, null, null, null));
        assertTrue(TransferResult.success(id).ok());
        assertFalse(TransferResult.failure(id, TransferError.BACKEND_DOWN).ok());
        assertEquals(TransferError.BACKEND_DOWN, TransferResult.failure(id, TransferError.BACKEND_DOWN).error());
    }

    @Test
    void errorTokensRoundTrip() {
        for (TransferError error : TransferError.values()) {
            assertEquals(error, TransferError.fromWireName(error.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> TransferError.fromWireName("banana"));
        for (TransferCause cause : TransferCause.values()) {
            assertEquals(cause, TransferCause.fromWireName(cause.wireName()));
        }
        assertEquals("mirage_teleport", TransferCause.MIRAGE_TELEPORT.wireName());
    }
}

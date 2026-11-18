package kz.bejiihiu.dimensions.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferTokensTest {

    @Test
    void errorWireNamesRoundTrip() {
        for (TransferError error : TransferError.values()) {
            assertEquals(error, TransferError.fromWireName(error.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> TransferError.fromWireName("bogus"));
    }

    @Test
    void errorWireNamesAreStable() {
        // pinned: the journal and metrics store these tokens
        assertEquals("timeout", TransferError.TIMEOUT.wireName());
        assertEquals("backend_down", TransferError.BACKEND_DOWN.wireName());
        assertEquals("unavailable", TransferError.UNAVAILABLE.wireName());
        assertEquals("rejected", TransferError.REJECTED.wireName());
        assertEquals("disconnected", TransferError.DISCONNECTED.wireName());
        assertEquals("unknown", TransferError.UNKNOWN.wireName());
    }

    @Test
    void causeWireNamesRoundTrip() {
        for (TransferCause cause : TransferCause.values()) {
            assertEquals(cause, TransferCause.fromWireName(cause.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> TransferCause.fromWireName("bogus"));
    }

    @Test
    void causeWireNamesAreStable() {
        assertEquals("nether_portal", TransferCause.NETHER_PORTAL.wireName());
        assertEquals("end_portal", TransferCause.END_PORTAL.wireName());
        assertEquals("respawn", TransferCause.RESPAWN.wireName());
        assertEquals("programmatic", TransferCause.PROGRAMMATIC.wireName());
        assertEquals("mirage_teleport", TransferCause.MIRAGE_TELEPORT.wireName());
    }
}

package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.paper.transfer.PositionValidator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherCausePositionValidatorTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private TransferOperation op(TransferCause cause) {
        return TransferOperation.created(UUID.randomUUID(), UUID.randomUUID(), "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER,
                new Position(0.0, 64.0, 0.0, 0.0, 0.0), cause, T0);
    }

    @Test
    void plainPortalArrivalsAreNeverVetoed() {
        PositionValidator delegate = (world, position, uuid) -> false;   // would reject everything
        NetherCausePositionValidator validator = new NetherCausePositionValidator(delegate);
        assertTrue(validator.isValidFor(null, new Position(0.0, 64.0, 0.0, 0.0, 0.0),
                UUID.randomUUID(), op(TransferCause.NETHER_PORTAL)));
    }

    @Test
    void nonPortalOperationsDeferToTheDelegate() {
        AtomicBoolean delegateCalled = new AtomicBoolean(false);
        PositionValidator delegate = (world, position, uuid) -> {
            delegateCalled.set(true);
            return false;
        };
        NetherCausePositionValidator validator = new NetherCausePositionValidator(delegate);
        boolean result = validator.isValidFor(null, new Position(0.0, 64.0, 0.0, 0.0, 0.0),
                UUID.randomUUID(), op(TransferCause.END_PORTAL));
        assertFalse(result);
        assertTrue(delegateCalled.get());
    }

    @Test
    void bareCheckStillUsesTheDelegate() {
        NetherCausePositionValidator validator =
                new NetherCausePositionValidator((world, position, uuid) -> false);
        assertFalse(validator.isValid(null, new Position(0.0, 64.0, 0.0, 0.0, 0.0), UUID.randomUUID()));
    }
}

package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlan;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlanResolver;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherArrivalPlanResolverTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private TransferOperation op(TransferCause cause, DimensionId from, DimensionId to, Position target) {
        return TransferOperation.created(UUID.randomUUID(), UUID.randomUUID(), "overworld", "nether",
                from, to, target, cause, T0);
    }

    private ArrivalPlanResolver recordingResolver() {
        return operation -> new ArrivalPlan.WorldSpawn();
    }

    @Test
    void netherOperationsGetTheJournalPositionAsProvisional() {
        NetherArrivalPlanResolver resolver = new NetherArrivalPlanResolver(recordingResolver());
        Position ideal = new Position(123.5, 70.0, -456.25, 90.0, 15.0);
        ArrivalPlan plan = resolver.resolve(op(TransferCause.NETHER_PORTAL,
                DimensionId.OVERWORLD, DimensionId.NETHER, ideal));
        ArrivalPlan.Stored stored = assertInstanceOf(ArrivalPlan.Stored.class, plan);
        assertEquals(null, stored.worldName());
        assertEquals(ideal, stored.position());
    }

    @Test
    void netherDestinationIsClampedToTheNetherDefaultBounds() {
        NetherArrivalPlanResolver resolver = new NetherArrivalPlanResolver(recordingResolver());
        Position outOfBounds = new Position(100_000_000.0, 70.0, -100_000_000.0, 0.0, 0.0);
        ArrivalPlan plan = resolver.resolve(op(TransferCause.NETHER_PORTAL,
                DimensionId.OVERWORLD, DimensionId.NETHER, outOfBounds));
        ArrivalPlan.Stored stored = assertInstanceOf(ArrivalPlan.Stored.class, plan);
        assertTrue(stored.position().x() <= 3_749_998.125, "clamped into the nether default border");
        assertTrue(stored.position().z() >= -3_749_998.125, "clamped into the nether default border");
    }

    @Test
    void overworldDestinationIsClampedToTheOverworldDefaultBounds() {
        NetherArrivalPlanResolver resolver = new NetherArrivalPlanResolver(recordingResolver());
        Position outOfBounds = new Position(-100_000_000.0, 70.0, 100_000_000.0, 0.0, 0.0);
        ArrivalPlan plan = resolver.resolve(op(TransferCause.NETHER_PORTAL,
                DimensionId.NETHER, DimensionId.OVERWORLD, outOfBounds));
        ArrivalPlan.Stored stored = assertInstanceOf(ArrivalPlan.Stored.class, plan);
        assertTrue(stored.position().x() >= -29_999_985.0, "clamped into the overworld default border");
        assertTrue(stored.position().z() <= 29_999_985.0, "clamped into the overworld default border");
    }

    @Test
    void nonPortalOperationsDelegateToTheWrappedResolver() {
        ArrivalPlan delegatePlan = new ArrivalPlan.WorldSpawn();
        ArrivalPlanResolver delegate = operation -> delegatePlan;
        NetherArrivalPlanResolver resolver = new NetherArrivalPlanResolver(delegate);
        ArrivalPlan plan = resolver.resolve(op(TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD,
                new Position(1.0, 2.0, 3.0, 0.0, 0.0)));
        assertSame(delegatePlan, plan);
    }

    @Test
    void nullOperationDelegatesSafely() {
        ArrivalPlan delegatePlan = new ArrivalPlan.WorldSpawn();
        NetherArrivalPlanResolver resolver =
                new NetherArrivalPlanResolver(operation -> delegatePlan);
        assertSame(delegatePlan, resolver.resolve(null));
    }
}

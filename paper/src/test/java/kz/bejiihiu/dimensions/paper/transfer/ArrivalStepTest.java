package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.paper.FakeArrivalWorldLookup;
import kz.bejiihiu.dimensions.paper.FakePlayers;
import kz.bejiihiu.dimensions.paper.TestWorlds;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link TransferArrivalService.PostClaimArrivalStep} seam: an arrival step applies to
 * the operations it claims, may replace the completion (the Nether flow swaps the provisional
 * journal position for the portal exit), and everything else takes the plain join path.
 * A misbehaving step can never double-settle or strand the player.
 */
class ArrivalStepTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Position IDEAL = new Position(123.5, 70.0, -456.25, 90.0, 0.0);

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private World overworld, nether;
    private FakeArrivalWorldLookup worlds;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        overworld = TestWorlds.create("world", World.Environment.NORMAL);
        nether = TestWorlds.create("world_nether", World.Environment.NETHER);
        worlds = new FakeArrivalWorldLookup(
                Map.of("world", overworld, "world_nether", nether), nether);
    }

    private TransferOperation seedPending(UUID playerUuid, TransferCause cause) {
        TransferOperation op = TransferOperation.created(
                UUID.randomUUID(), playerUuid, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.NETHER, IDEAL, cause, T0);
        backend.journalFake().recordCreated(op);
        return op;
    }

    private Player fakePlayer(UUID playerUuid) {
        // spawned at the provisional spot, as the spawn event would place them
        return FakePlayers.create(playerUuid,
                new Location(nether, IDEAL.x(), IDEAL.y(), IDEAL.z(), (float) IDEAL.yaw(), (float) IDEAL.pitch()),
                true);
    }

    private TransferArrivalService newArrivals(TransferArrivalService.PostClaimArrivalStep step) {
        return new TransferArrivalService(backend, Clock.fixed(T0, ZoneOffset.UTC),
                ArrivalPlanResolver.journalPosition(), PositionValidator.ALWAYS, step);
    }

    @Test
    void stepAppliesAndItsPositionWins() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.NETHER_PORTAL);
        Player player = fakePlayer(playerUuid);
        AtomicInteger applied = new AtomicInteger(0);
        Location portalExit = new Location(nether, 100.0, 66.0, -30.0, 45.0f, 5.0f);

        TransferArrivalService.PostClaimArrivalStep step = new TransferArrivalService.PostClaimArrivalStep() {
            @Override
            public boolean appliesTo(TransferOperation operation) {
                return operation.cause() == TransferCause.NETHER_PORTAL;
            }

            @Override
            public void run(TransferArrivalService.ArrivalCompletion completion, Player runPlayer,
                            ArrivalWorldLookup runWorlds, Consumer<TransferArrivalService.ArrivalCompletion> done) {
                applied.incrementAndGet();
                done.accept(new TransferArrivalService.ArrivalCompletion(
                        completion.operationId(), completion.operation(), portalExit));
            }
        };
        TransferArrivalService arrivals = newArrivals(step);
        assertTrue(arrivals.claimAndPlan(playerUuid, worlds).isPresent());

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, applied.get());
        Location landed = FakePlayers.location(player);
        assertEquals(100.0, landed.getX());
        assertEquals(66.0, landed.getY());
        assertEquals(-30.0, landed.getZ());
        assertEquals(45.0f, landed.getYaw());
        assertEquals(OperationState.COMMITTED, backend.journalFake().findById(op.operationId()).orElseThrow().state());
        assertEquals(1, backend.eventRecorder().completes().size());
    }

    @Test
    void stepIsNotAppliedToOtherCauses() {
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.END_PORTAL);
        Player player = fakePlayer(playerUuid);
        AtomicInteger applied = new AtomicInteger(0);

        TransferArrivalService.PostClaimArrivalStep step = new TransferArrivalService.PostClaimArrivalStep() {
            @Override
            public boolean appliesTo(TransferOperation operation) {
                return operation.cause() == TransferCause.NETHER_PORTAL;
            }

            @Override
            public void run(TransferArrivalService.ArrivalCompletion completion, Player runPlayer,
                            ArrivalWorldLookup runWorlds, Consumer<TransferArrivalService.ArrivalCompletion> done) {
                applied.incrementAndGet();
                done.accept(completion);
            }
        };
        TransferArrivalService arrivals = newArrivals(step);
        assertTrue(arrivals.claimAndPlan(playerUuid, worlds).isPresent());

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(0, applied.get());
        assertEquals(1, backend.eventRecorder().completes().size());
    }

    @Test
    void throwingStepFallsBackToTheProvisionalPlacement() {
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.NETHER_PORTAL);
        Player player = fakePlayer(playerUuid);

        TransferArrivalService.PostClaimArrivalStep step = new TransferArrivalService.PostClaimArrivalStep() {
            @Override
            public boolean appliesTo(TransferOperation operation) {
                return true;
            }

            @Override
            public void run(TransferArrivalService.ArrivalCompletion completion, Player runPlayer,
                            ArrivalWorldLookup runWorlds, Consumer<TransferArrivalService.ArrivalCompletion> done) {
                throw new IllegalStateException("boom (test)");
            }
        };
        TransferArrivalService arrivals = newArrivals(step);
        assertTrue(arrivals.claimAndPlan(playerUuid, worlds).isPresent());

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        // player stayed at the provisional spot; the flow completed anyway
        Location landed = FakePlayers.location(player);
        assertEquals(IDEAL.x(), landed.getX());
        assertEquals(1, backend.eventRecorder().completes().size());
    }

    @Test
    void doubleDoneStepSettlesOnlyOnce() {
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.NETHER_PORTAL);
        Player player = fakePlayer(playerUuid);

        TransferArrivalService.PostClaimArrivalStep step = new TransferArrivalService.PostClaimArrivalStep() {
            @Override
            public boolean appliesTo(TransferOperation operation) {
                return true;
            }

            @Override
            public void run(TransferArrivalService.ArrivalCompletion completion, Player runPlayer,
                            ArrivalWorldLookup runWorlds, Consumer<TransferArrivalService.ArrivalCompletion> done) {
                done.accept(completion);
                done.accept(completion);   // misbehaving step — must not settle twice
            }
        };
        TransferArrivalService arrivals = newArrivals(step);
        assertTrue(arrivals.claimAndPlan(playerUuid, worlds).isPresent());

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, backend.eventRecorder().completes().size());
    }
}

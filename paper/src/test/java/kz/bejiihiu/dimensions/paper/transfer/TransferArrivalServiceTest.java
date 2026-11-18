package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.persistence.RespawnPointStore;
import kz.bejiihiu.dimensions.common.persistence.PersistenceException;
import kz.bejiihiu.dimensions.paper.FakeArrivalWorldLookup;
import kz.bejiihiu.dimensions.paper.FakePlayers;
import kz.bejiihiu.dimensions.paper.TestWorlds;
import kz.bejiihiu.dimensions.paper.end.EndArrivalPlanResolver;
import kz.bejiihiu.dimensions.paper.sync.FakeSyncOperations;
import kz.bejiihiu.dimensions.paper.sync.TimeoutReason;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Target-side arrival: claim CAS, plan resolution (End entry vs exit with and without
 * respawn records), spawn placement, validator fallback, chunk tickets, terminal journal
 * transitions, the sync gate (happy path, timeout, missing services, disconnect mid-wait,
 * degraded reports, no duplicate events on retry) and the Complete event contract.
 */
class TransferArrivalServiceTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Position END_ARRIVAL = new Position(100.5, 49.0, 0.5, 90.0, 0.0);

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private FakeSyncOperations sync;
    private TransferArrivalService arrivals;
    private World overworld, nether, endWorld;
    private FakeArrivalWorldLookup worlds;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        sync = new FakeSyncOperations();
        overworld = TestWorlds.create("world", World.Environment.NORMAL);
        nether = TestWorlds.create("world_nether", World.Environment.NETHER);
        endWorld = TestWorlds.create("world_the_end", World.Environment.THE_END);
        worlds = new FakeArrivalWorldLookup(
                Map.of("world", overworld, "world_nether", nether, "world_the_end", endWorld),
                overworld);
        arrivals = newTransferArrivals(PositionValidator.ALWAYS);
    }

    private TransferArrivalService newTransferArrivals(PositionValidator validator) {
        EndArrivalPlanResolver resolver = new EndArrivalPlanResolver(backend);
        return new TransferArrivalService(backend, Clock.fixed(T0, ZoneOffset.UTC),
                resolver, validator, () -> sync);
    }

    private TransferOperation seedPending(UUID playerUuid, TransferCause cause,
                                          DimensionId from, DimensionId to, Position position) {
        TransferOperation op = TransferOperation.created(
                UUID.randomUUID(), playerUuid, "end", "overworld", from, to, position, cause, T0);
        backend.journalFake().recordCreated(op);
        return op;
    }

    private Optional<TransferArrivalService.ArrivalClaim> claim(UUID playerUuid) {
        return arrivals.claimAndPlan(playerUuid, worlds);
    }

    @Test
    void joinWithoutPendingOperationIsANoOp() {
        Player player = FakePlayers.create(UUID.randomUUID(),
                new Location(overworld, 1.0, 64.0, 1.0, 0.0f, 0.0f), true);
        arrivals.onJoin(player, worlds);
        assertTrue(backend.journalFake().rows().isEmpty());
        assertTrue(backend.eventRecorder().completes().isEmpty());
    }

    @Test
    void recordedWorldMissingFallsBackToWorldSpawn() {
        UUID playerUuid = UUID.randomUUID();
        Position bedPos = new Position(10.5, 64.0, 11.5, 90.0, 0.0);
        backend.respawnStore().upsert(new RespawnPointRecord(playerUuid,
                DimensionId.OVERWORLD, "gone_world", bedPos, RespawnKind.BED, T0));
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, bedPos);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        Location location = claimed.get().location();
        assertNotNull(location);
        assertEquals(overworld.getSpawnLocation().getX(), location.getX());
        assertEquals(overworld.getSpawnLocation().getY(), location.getY());
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("gone_world")));
    }

    @Test
    void endEntryClaimsAndPlansObsidianPlatform() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        assertTrue(claimed.get().plan() instanceof ArrivalPlan.Stored);
        ArrivalPlan.Stored stored = (ArrivalPlan.Stored) claimed.get().plan();
        assertNull(stored.worldName(), "entry arrival lands in the backend's respawn world");
        assertEquals(END_ARRIVAL, stored.position());
        assertEquals(overworld, claimed.get().location().getWorld());
        assertEquals(100.5, claimed.get().location().getX());
        // journal row claimed
        assertEquals(OperationState.TARGET_CREATED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
    }

    @Test
    void exitPortalWithBedRecordPlansTheBedWorld() {
        UUID playerUuid = UUID.randomUUID();
        Position bedPos = new Position(10.5, 64.0, 11.5, 90.0, 0.0);
        RespawnPointRecord bed = new RespawnPointRecord(playerUuid, DimensionId.OVERWORLD, "world",
                bedPos, RespawnKind.BED, T0);
        backend.respawnStore().upsert(bed);
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, bedPos);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        ArrivalPlan.Stored stored = (ArrivalPlan.Stored) claimed.get().plan();
        assertEquals("world", stored.worldName());
        assertEquals(bedPos, stored.position());
        assertEquals(overworld, claimed.get().location().getWorld());
    }

    @Test
    void exitPortalWithAnchorRecordPlansTheAnchorWorld() {
        UUID playerUuid = UUID.randomUUID();
        Position anchorPos = new Position(-100.5, 70.0, 200.5, 180.0, 0.0);
        RespawnPointRecord anchor = new RespawnPointRecord(playerUuid, DimensionId.NETHER, "world_nether",
                anchorPos, RespawnKind.RESPAWN_ANCHOR, T0);
        backend.respawnStore().upsert(anchor);
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.NETHER, anchorPos);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        ArrivalPlan.Stored stored = (ArrivalPlan.Stored) claimed.get().plan();
        assertEquals("world_nether", stored.worldName());
        assertEquals(anchorPos, stored.position());
        assertEquals(nether, claimed.get().location().getWorld());
    }

    @Test
    void exitPortalWithoutRecordPlansWorldSpawn() {
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, END_ARRIVAL);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        assertTrue(claimed.get().plan() instanceof ArrivalPlan.WorldSpawn);
        assertEquals(overworld, claimed.get().location().getWorld());
        assertEquals(overworld.getSpawnLocation().getX(), claimed.get().location().getX());
    }

    @Test
    void exitPortalWithUnroutableRecordDimensionPlansWorldSpawn() {
        UUID playerUuid = UUID.randomUUID();
        Position bedPos = new Position(10.5, 64.0, 11.5, 0.0, 0.0);
        backend.respawnStore().upsert(new RespawnPointRecord(playerUuid,
                DimensionId.of("mymod:fragments/void"), "custom_world",
                bedPos, RespawnKind.BED, T0));
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, bedPos);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        assertTrue(claimed.get().plan() instanceof ArrivalPlan.WorldSpawn,
                "a record that cannot route anywhere must degrade to world spawn, not a void");
        assertEquals(overworld, claimed.get().location().getWorld());
    }

    @Test
    void unreadableRespawnStoreDegradesToWorldSpawn() {
        UUID playerUuid = UUID.randomUUID();
        backend.setRespawnPoints(new RespawnPointStore() {
            @Override public void upsert(RespawnPointRecord point) { }
            @Override public Optional<RespawnPointRecord> find(UUID player) {
                throw new PersistenceException("store down (test)");
            }
            @Override public boolean delete(UUID player) { return false; }
        });
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, END_ARRIVAL);

        Optional<TransferArrivalService.ArrivalClaim> claimed = claim(playerUuid);

        assertTrue(claimed.isPresent());
        assertTrue(claimed.get().plan() instanceof ArrivalPlan.WorldSpawn);
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("respawn store unavailable")));
    }

    @Test
    void fullArrivalCommitTransitionsJournalAndFiresCompleteEvent() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.5, 49.0, 0.5, 90.0f, 0.0f), true);
        assertTrue(claim(playerUuid).isPresent());

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);   // runOn + 2-tick complete delay

        TransferOperation done = backend.journalFake().findById(op.operationId()).orElseThrow();
        assertEquals(OperationState.COMMITTED, done.state());
        assertEquals(1, backend.eventRecorder().completes().size());
        assertEquals(op.operationId(), backend.eventRecorder().completes().get(0).operationId());
        assertTrue(backend.eventRecorder().completes().get(0).durationMillis() >= 0);
        assertTrue(ticketsAt(100.5, 0.5), "arrival chunk must hold a plugin ticket");

        // a late claim for the same op cannot happen (op is terminal)
        assertTrue(claim(playerUuid).isEmpty());
    }

    @Test
    void storedPositionMateriallyDifferentTriggersTeleport() {
        UUID playerUuid = UUID.randomUUID();
        Position bedPos = new Position(10.5, 64.0, 11.5, 90.0, 0.0);
        backend.respawnStore().upsert(new RespawnPointRecord(playerUuid,
                DimensionId.OVERWORLD, "world", bedPos, RespawnKind.BED, T0));
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, bedPos);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.0, 64.0, 100.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        Location landed = FakePlayers.location(player);
        assertEquals(10.5, landed.getX());
        assertEquals(11.5, landed.getZ());
    }

    @Test
    void validatorFallbackSendsPlayerToWorldSpawnButCompletes() {
        UUID playerUuid = UUID.randomUUID();
        Position bedPos = new Position(10.5, 64.0, 11.5, 0.0, 0.0);
        backend.respawnStore().upsert(new RespawnPointRecord(playerUuid,
                DimensionId.OVERWORLD, "world", bedPos, RespawnKind.BED, T0));
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.THE_END, DimensionId.OVERWORLD, bedPos);
        arrivals = newTransferArrivals((world, position, uuid) -> false);   // bed missing
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 99.0, 64.0, 99.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        Location landed = FakePlayers.location(player);
        assertEquals(overworld.getSpawnLocation().getX(), landed.getX());
        assertEquals(1, backend.eventRecorder().completes().size());
    }

    @Test
    void completeEventSkippedWhenOperationTerminatedConcurrently() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.5, 49.0, 0.5, 90.0f, 0.0f), true);
        claim(playerUuid);

        // simulate the source-side watchdog terminating the op before finalization
        backend.journalFake().compareAndSetState(op.operationId(), OperationState.TARGET_CREATED,
                op.withState(OperationState.FAILED, T0,
                        kz.bejiihiu.dimensions.common.model.TransferError.TIMEOUT, "concurrently failed"));

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertTrue(backend.eventRecorder().completes().isEmpty());
        assertEquals(OperationState.FAILED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
    }

    @Test
    void playerQuittingBeforeJoinFailsTheOperationViaClaimTimeout() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        claim(playerUuid);
        // player never joins (kicked during configuration phase)

        backend.fakeSchedulers().advanceMillis(2L * 10_000L + 1);

        TransferOperation row = backend.journalFake().findById(op.operationId()).orElseThrow();
        assertEquals(OperationState.FAILED, row.state());
        assertTrue(backend.eventRecorder().completes().isEmpty());
    }

    @Test
    void syncReadyProceedsWithTeleportAndCompleteEvent() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(List.of(playerUuid), sync.awaitedPlayers());
        Location landed = FakePlayers.location(player);
        assertEquals(100.5, landed.getX());   // placement gated on sync, applied once ready
        assertEquals(0.5, landed.getZ());
        assertEquals(1, backend.eventRecorder().completes().size());
        assertEquals(op.operationId(), backend.eventRecorder().completes().get(0).operationId());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertEquals(OperationState.COMMITTED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
    }

    @Test
    void syncTimeoutSkipsTeleportAndFailsTheOperation() {
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Location joinSpot = new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f);
        Player player = FakePlayers.create(playerUuid, joinSpot, true);
        claim(playerUuid);
        sync.setTimeout(TimeoutReason.WAIT_TIMEOUT);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        Location landed = FakePlayers.location(player);
        assertEquals(5.0, landed.getX());   // NOT moved — keeps the provisional spawn placement
        assertEquals(5.0, landed.getZ());
        assertTrue(backend.eventRecorder().completes().isEmpty());
        List<RecordingTransferEventDispatcher.FailedCall> failures = backend.eventRecorder().failures();
        assertEquals(1, failures.size());
        assertEquals(TransferError.TIMEOUT, failures.get(0).error());
        assertEquals(OperationState.TARGET_CREATED, failures.get(0).phase());
        assertTrue(failures.get(0).playerOnline());
        TransferOperation row = backend.journalFake().findById(op.operationId()).orElseThrow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.TIMEOUT, row.error());
        assertNotNull(row.errorMessage());
        assertTrue(backend.severeLog().stream().anyMatch(s ->
                s.contains(playerUuid.toString()) && s.contains("timeout")));

        // the claim watchdog cannot re-fire once the row is terminal and pending is cleared
        backend.fakeSchedulers().advanceMillis(20_001L);
        assertEquals(1, backend.eventRecorder().failures().size());
        assertTrue(backend.eventRecorder().completes().isEmpty());
    }

    @Test
    void syncServicesMissingProceedsDegradedWithWarning() {
        EndArrivalPlanResolver resolver = new EndArrivalPlanResolver(backend);
        TransferArrivalService missing = new TransferArrivalService(backend, Clock.fixed(T0, ZoneOffset.UTC),
                resolver, PositionValidator.ALWAYS, () -> null);
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.5, 49.0, 0.5, 90.0f, 0.0f), true);
        assertTrue(missing.claimAndPlan(playerUuid, worlds).isPresent());

        missing.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, backend.eventRecorder().completes().size());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertEquals(OperationState.COMMITTED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
        assertTrue(backend.warningLog().stream().anyMatch(w ->
                w.contains("WITHOUT sync confirmation") && w.contains(playerUuid.toString())));
    }

    @Test
    void syncLookupThrowingProceedsDegradedWithWarning() {
        EndArrivalPlanResolver resolver = new EndArrivalPlanResolver(backend);
        TransferArrivalService broken = new TransferArrivalService(backend, Clock.fixed(T0, ZoneOffset.UTC),
                resolver, PositionValidator.ALWAYS, () -> {
                    throw new IllegalStateException("boot failed");
                });
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.5, 49.0, 0.5, 90.0f, 0.0f), true);
        assertTrue(broken.claimAndPlan(playerUuid, worlds).isPresent());

        broken.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, backend.eventRecorder().completes().size());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertTrue(backend.warningLog().stream().anyMatch(w -> w.contains("lookup failed")));
    }

    @Test
    void disconnectDuringSyncWaitFailsOfflineWithoutTeleport() {
        sync.setDeferred(true);   // the wait stays pending; the "disconnect" arrives while it is armed
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Location joinSpot = new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f);
        Player player = FakePlayers.create(playerUuid, joinSpot, true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        assertEquals(List.of(playerUuid), sync.awaitedPlayers());   // wait armed; nothing placed yet

        // the sync plugin reports the player left mid-wait (its online check fires PLAYER_OFFLINE)
        sync.failPending(TimeoutReason.PLAYER_OFFLINE);
        backend.fakeSchedulers().advanceTicks(2);

        Location landed = FakePlayers.location(player);
        assertEquals(5.0, landed.getX());   // still never moved
        assertTrue(backend.eventRecorder().completes().isEmpty());
        List<RecordingTransferEventDispatcher.FailedCall> failures = backend.eventRecorder().failures();
        assertEquals(1, failures.size());
        assertEquals(TransferError.DISCONNECTED, failures.get(0).error());
        assertEquals(OperationState.TARGET_CREATED, failures.get(0).phase());
        // the proxy player still reports online=true; the PLAYER_OFFLINE reason is authoritative
        assertFalse(failures.get(0).playerOnline());
        TransferOperation row = backend.journalFake().findById(op.operationId()).orElseThrow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.DISCONNECTED, row.error());
        assertTrue(backend.severeLog().stream().anyMatch(s -> s.contains(playerUuid.toString())));
    }

    @Test
    void degradedSyncReportStillCompletesTheArrival() {
        sync.setReport(FakeSyncOperations.degradedReport());
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, backend.eventRecorder().completes().size());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertEquals(OperationState.COMMITTED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
        Location landed = FakePlayers.location(player);
        assertEquals(100.5, landed.getX());   // degraded=yes still proceeds, never blocks
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("degraded=true")));
    }

    @Test
    void awaitSyncThrowingFailsWithUnavailable() {
        sync.setThrowOnAwait(true);
        UUID playerUuid = UUID.randomUUID();
        TransferOperation op = seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(2);

        assertTrue(backend.eventRecorder().completes().isEmpty());
        List<RecordingTransferEventDispatcher.FailedCall> failures = backend.eventRecorder().failures();
        assertEquals(1, failures.size());
        assertEquals(TransferError.UNAVAILABLE, failures.get(0).error());
        assertEquals(OperationState.FAILED,
                backend.journalFake().findById(op.operationId()).orElseThrow().state());
    }

    @Test
    void retriedJoinAfterCompletionDispatchesNoDuplicateEvents() {
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 100.5, 49.0, 0.5, 90.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);
        // a duplicated join (double login packet, retried delivery) must not re-fire anything
        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);

        assertEquals(1, backend.eventRecorder().completes().size());
        assertTrue(backend.eventRecorder().failures().isEmpty());
    }

    @Test
    void retriedJoinAfterFailureDispatchesNoDuplicateEvents() {
        sync.setTimeout(TimeoutReason.WAIT_TIMEOUT);
        UUID playerUuid = UUID.randomUUID();
        seedPending(playerUuid, TransferCause.END_PORTAL,
                DimensionId.OVERWORLD, DimensionId.THE_END, END_ARRIVAL);
        Player player = FakePlayers.create(playerUuid,
                new Location(overworld, 5.0, 80.0, 5.0, 0.0f, 0.0f), true);
        claim(playerUuid);

        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);
        arrivals.onJoin(player, worlds);
        backend.fakeSchedulers().advanceTicks(3);
        backend.fakeSchedulers().advanceMillis(20_001L);   // claim watchdog window passes

        assertEquals(1, backend.eventRecorder().failures().size());
        assertTrue(backend.eventRecorder().completes().isEmpty());
    }

    private boolean ticketsAt(double x, double z) {
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        return backend.ticketGateway().adds().stream()
                .anyMatch(k -> k.chunkX() == cx && k.chunkZ() == cz);
    }
}




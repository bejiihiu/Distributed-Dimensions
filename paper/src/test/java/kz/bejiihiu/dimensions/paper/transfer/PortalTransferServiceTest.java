package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.common.protocol.ProtocolMessage;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-side orchestration of a portal transfer: fail-closed health gate, journal state
 * machine, api-event contract, proxy round-trip and the expiry watchdog.
 */
class PortalTransferServiceTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Position ARRIVAL = new Position(100.5, 49.0, 0.5, 90.0, 0.0);
    private static final TransferCause CAUSE = TransferCause.END_PORTAL;

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private PortalTransferService service;
    private Player player;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        service = new PortalTransferService(backend, Clock.fixed(T0, ZoneOffset.UTC));
        backend.channelFake().install(service);   // production wires this in the module install
        World overworld = TestWorlds.create("world");
        player = FakePlayers.create(UUID.randomUUID(),
                new Location(overworld, 100.0, 64.0, 100.0, 90.0f, 0.0f), true);
    }

    private TransferOperation onlyRow() {
        List<TransferOperation> rows = backend.journalFake().rows();
        assertEquals(1, rows.size(), "expected exactly one journal row");
        return rows.get(0);
    }

    private static byte[] encodeUnchecked(ProtocolMessage message) {
        try {
            return ProtocolCodec.encodeToBytes(message);
        } catch (kz.bejiihiu.dimensions.common.protocol.ProtocolEncodeException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProtocolMessage decodeUnchecked(byte[] payload) {
        try {
            return ProtocolCodec.decode(payload);
        } catch (kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unhealthyBackendRefusesBeforeAnyDispatch() {
        FakeTransferBackend degraded = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir), false);
        PortalTransferService failing = new PortalTransferService(degraded, Clock.fixed(T0, ZoneOffset.UTC));

        PortalTransferService.Outcome outcome = failing.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);

        assertTrue(outcome instanceof PortalTransferService.Outcome.Refused);
        assertTrue(backend.journalFake().rows().isEmpty());
        assertTrue(degraded.eventRecorder().starts().isEmpty());
        assertTrue(degraded.channelFake().sent().isEmpty());
    }

    @Test
    void unroutableTargetDimensionRefuses() {
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.of("mymod:void"), CAUSE, ARRIVAL);

        assertTrue(outcome instanceof PortalTransferService.Outcome.Refused);
        assertTrue(backend.journalFake().rows().isEmpty());
        assertTrue(backend.channelFake().sent().isEmpty());
    }

    @Test
    void happyPathJournalsPreparesSendsAndSchedulesWatchdog() {
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);

        assertTrue(outcome instanceof PortalTransferService.Outcome.Started);
        UUID operationId = ((PortalTransferService.Outcome.Started) outcome).operationId();

        TransferOperation row = onlyRow();
        assertEquals(OperationState.PREPARING, row.state());
        assertEquals(TransferCause.END_PORTAL, row.cause());
        assertEquals(DimensionId.OVERWORLD, row.sourceDimension());
        assertEquals(DimensionId.THE_END, row.targetDimension());
        assertEquals("overworld", row.sourceServer());
        assertEquals("end", row.targetServer());
        assertEquals(ARRIVAL, row.targetPosition());

        // exactly one start event, from the triggering thread's perspective
        assertEquals(1, backend.eventRecorder().starts().size());
        assertEquals(operationId, backend.eventRecorder().starts().get(0).operationId());

        // the proxy received a decodable request on the channel
        assertEquals(1, backend.channelFake().sent().size());
        RecordingTransferChannel.Sent sent = backend.channelFake().sent().get(0);
        assertEquals(player, sent.player());
        ProtocolMessage decoded = decodeUnchecked(sent.payload());
        assertTrue(decoded instanceof DimensionTransferRequestMessage);
        DimensionTransferRequestMessage request = (DimensionTransferRequestMessage) decoded;
        assertEquals(operationId, request.requestId());
        assertEquals(ARRIVAL, request.targetPosition());

        // the watchdog is scheduled
        assertTrue(backend.fakeSchedulers().pendingTasks() >= 1);
    }

    @Test
    void cancelledStartLeavesNoRequestAndNoFailureEvent() {
        backend.eventRecorder().setCancelStart(true);
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);

        assertTrue(outcome instanceof PortalTransferService.Outcome.Cancelled);
        UUID operationId = ((PortalTransferService.Outcome.Cancelled) outcome).operationId();

        assertTrue(backend.channelFake().sent().isEmpty());
        assertTrue(backend.eventRecorder().failures().isEmpty(), "a cancelled start is not a failure");
        TransferOperation row = onlyRow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.REJECTED, row.error());
        assertEquals(operationId, row.operationId());
    }

    @Test
    void channelSendFailureFailsTheOperationAndFiresFailedEvent() {
        backend.channelFake().setFailSend(true);
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);

        assertTrue(outcome instanceof PortalTransferService.Outcome.Refused);
        TransferOperation row = onlyRow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.UNAVAILABLE, row.error());

        assertEquals(1, backend.eventRecorder().failures().size());
        RecordingTransferEventDispatcher.FailedCall failure = backend.eventRecorder().failures().get(0);
        assertEquals(TransferError.UNAVAILABLE, failure.error());
        assertEquals(OperationState.PREPARING, failure.phase());
        assertTrue(failure.playerOnline());
    }

    @Test
    void watchdogFailsAStuckOperationAsTimeout() {
        service.transfer(player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);
        assertEquals(OperationState.PREPARING, onlyRow().state());

        // watchdog is scheduled at 2x the configured timeout (10s -> 20s -> 400 ticks)
        backend.fakeSchedulers().advanceTicks(401);

        TransferOperation row = onlyRow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.TIMEOUT, row.error());
        assertEquals(1, backend.eventRecorder().failures().size());
        assertEquals(TransferError.TIMEOUT, backend.eventRecorder().failures().get(0).error());
        assertEquals(OperationState.PREPARING, backend.eventRecorder().failures().get(0).phase());
    }

    @Test
    void proxyResultFailureFailsTheOperationExactlyOnce() {
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);
        UUID operationId = ((PortalTransferService.Outcome.Started) outcome).operationId();

        DimensionTransferResultMessage failure =
                new DimensionTransferResultMessage(false, operationId, null, TransferError.BACKEND_DOWN, "no route");
        backend.channelFake().deliver(player, encodeUnchecked(failure));
        backend.fakeSchedulers().advanceMillis(0);   // flush the async hand-off

        TransferOperation row = onlyRow();
        assertEquals(OperationState.FAILED, row.state());
        assertEquals(TransferError.BACKEND_DOWN, row.error());
        assertEquals(1, backend.eventRecorder().failures().size());
        assertEquals(operationId, backend.eventRecorder().failures().get(0).operationId());

        // a second identical failure result is dropped, not double-fired
        backend.channelFake().deliver(player, encodeUnchecked(failure));
        backend.fakeSchedulers().advanceMillis(0);
        assertEquals(1, backend.eventRecorder().failures().size());
    }

    @Test
    void proxyResultSuccessLeavesTheOperationForTheTarget() {
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);
        UUID operationId = ((PortalTransferService.Outcome.Started) outcome).operationId();

        DimensionTransferResultMessage ok =
                new DimensionTransferResultMessage(true, operationId, null, null, null);
        backend.channelFake().deliver(player, encodeUnchecked(ok));
        backend.fakeSchedulers().advanceMillis(0);

        assertEquals(OperationState.PREPARING, onlyRow().state());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertTrue(backend.eventRecorder().completes().isEmpty());
    }

    @Test
    void lateFailureResultAfterTerminalIsDropped() {
        PortalTransferService.Outcome outcome = service.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, CAUSE, ARRIVAL);
        UUID operationId = ((PortalTransferService.Outcome.Started) outcome).operationId();
        backend.fakeSchedulers().advanceTicks(401);   // watchdog terminates it

        DimensionTransferResultMessage failure =
                new DimensionTransferResultMessage(false, operationId, null, TransferError.TIMEOUT, "too late");
        backend.channelFake().deliver(player, encodeUnchecked(failure));
        backend.fakeSchedulers().advanceMillis(0);

        assertEquals(TransferError.TIMEOUT, onlyRow().error());
        assertEquals(1, backend.eventRecorder().failures().size());
    }

    @Test
    void resultForUnknownOperationIsDropped() {
        DimensionTransferResultMessage failure = new DimensionTransferResultMessage(
                false, UUID.randomUUID(), null, TransferError.UNKNOWN, "ghost");
        backend.channelFake().deliver(player, encodeUnchecked(failure));
        backend.fakeSchedulers().advanceMillis(0);

        assertTrue(backend.journalFake().rows().isEmpty());
        assertTrue(backend.eventRecorder().failures().isEmpty());
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("unknown operation")));
    }

    @Test
    void malformedPayloadIsDropped() {
        backend.channelFake().deliver(player, "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        backend.fakeSchedulers().advanceMillis(0);

        assertTrue(backend.journalFake().rows().isEmpty());
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("undecodable")));
    }
}




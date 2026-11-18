package kz.bejiihiu.dimensions.velocity.transfer;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolMessage;
import kz.bejiihiu.dimensions.velocity.support.AdjustableClock;
import kz.bejiihiu.dimensions.velocity.support.FakeResult;
import kz.bejiihiu.dimensions.velocity.support.FakeServerConnection;
import kz.bejiihiu.dimensions.velocity.support.FakeTransferPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transfer pipeline end to end, with a programmable proxy runtime: validation → idempotency
 * gate → connect → completion → result delivery.
 */
class TransferCoordinatorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("test");
    private static final UUID PLAYER = UUID.randomUUID();
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("distributeddimensions", "transfer");

    private static final DimensionsSettings DIMENSIONS = new DimensionsSettings(
            new DimensionTarget("overworld", "minecraft:overworld"),
            new DimensionTarget("nether", "minecraft:the_nether"),
            new DimensionTarget("end", "minecraft:the_end"));

    private FakeTransferPlatform platform;
    private FakeServerConnection source;
    private TransferOperations operations;
    private TransferCoordinator coordinator;
    private boolean healthy;

    @BeforeEach
    void setUp() {
        platform = new FakeTransferPlatform();
        source = new FakeServerConnection("overworld", 25_651);
        platform.setCurrentConnection(PLAYER, source);
        platform.registerServer("nether");
        platform.registerServer("end");
        operations = new TransferOperations(new AdjustableClock(Instant.parse("2026-11-18T12:00:00Z")));
        healthy = true;
        coordinator = new TransferCoordinator(platform, operations,
                () -> healthy,
                () -> DIMENSIONS,
                () -> Duration.ofSeconds(5),
                LOGGER, CHANNEL);
    }

    private DimensionTransferRequestMessage request(String sourceServer, String targetServer,
                                                    String targetDimension, UUID requestId) {
        return new DimensionTransferRequestMessage(requestId, PLAYER, sourceServer, targetServer,
                DimensionId.OVERWORLD, DimensionId.of(targetDimension),
                new Position(1.5, 64.0, -2.5, 90.0, 0.0), TransferCause.NETHER_PORTAL);
    }

    private DimensionTransferRequestMessage defaultRequest(UUID requestId) {
        return request("overworld", "nether", "minecraft:the_nether", requestId);
    }

    private DimensionTransferResultMessage lastResult() {
        ProtocolMessage message = platform.lastSentMessage();
        return assertInstanceOf(DimensionTransferResultMessage.class, message);
    }

    private void awaitSent(int expected, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (platform.sent.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for outbound messages");
            }
        }
    }

    // --------------------------------------------------------------- happy paths

    @Test
    void successConnectsAndDeliversOkResult() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, platform.connectAttempts.size());
        assertEquals(CHANNEL, platform.sent.get(0).channel());
        DimensionTransferResultMessage result = lastResult();
        assertTrue(result.ok());
        assertEquals(requestId, result.requestId());
        assertEquals(0, operations.pendingSize());
        assertEquals(1, operations.stats().connectsSucceeded.get());
        assertEquals(1, operations.recent(10).size());
        assertEquals(TransferOperations.OutcomeKind.SUCCESS, operations.recent(10).get(0).kind());
    }

    @Test
    void alreadyConnectedIsTreatedAsSuccess() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(
                new FakeResult(ConnectionRequestBuilder.Status.ALREADY_CONNECTED)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertTrue(lastResult().ok());
        assertEquals(1, operations.stats().connectsSucceeded.get());
    }

    @Test
    void connectionInProgressIsTreatedAsSuccessish() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(
                new FakeResult(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        DimensionTransferResultMessage result = lastResult();
        assertTrue(result.ok());
        assertNotNull(result.message());
    }

    // --------------------------------------------------------------- failure paths

    @Test
    void tcpFailureMapsToBackendDown() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.failedFuture(new ConnectException("connection refused")));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        DimensionTransferResultMessage result = lastResult();
        assertFalse(result.ok());
        assertEquals(TransferError.BACKEND_DOWN, result.error());
        assertEquals(1, operations.stats().connectsFailed.get());
        assertEquals(0, operations.pendingSize());
    }

    @Test
    void backendDisconnectDuringLoginMapsToUnavailable() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(
                new FakeResult(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(TransferError.UNAVAILABLE, lastResult().error());
    }

    @Test
    void serverConnectCancelledByPolicyMapsToRejected() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(
                new FakeResult(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(TransferError.REJECTED, lastResult().error());
    }

    @Test
    void transferTimesOutAndBackendGetsTimeoutResult() {
        coordinator = new TransferCoordinator(platform, operations,
                () -> healthy, () -> DIMENSIONS, () -> Duration.ofMillis(150), LOGGER, CHANNEL);
        UUID requestId = UUID.randomUUID();
        CompletableFuture<ConnectionRequestBuilder.Result> stuck = new CompletableFuture<>();
        platform.setConnectFuture(stuck);

        coordinator.handleTransferRequest(defaultRequest(requestId), source);
        awaitSent(1, 5_000);

        DimensionTransferResultMessage result = lastResult();
        assertFalse(result.ok());
        assertEquals(TransferError.TIMEOUT, result.error());
        assertEquals(1, operations.stats().connectsTimedOut.get());
        assertEquals(0, operations.pendingSize(), "operation must leave the in-flight set on timeout");
    }

    // --------------------------------------------------------------- idempotency

    @Test
    void inFlightDuplicateDoesNotIssueASecondConnect() {
        UUID requestId = UUID.randomUUID();
        CompletableFuture<ConnectionRequestBuilder.Result> stuck = new CompletableFuture<>();
        platform.setConnectFuture(stuck);

        coordinator.handleTransferRequest(defaultRequest(requestId), source);
        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, platform.connectAttempts.size(), "duplicate request must not trigger a second connect");
        assertEquals(0, platform.sent.size(), "no result until the in-flight operation resolves");
        assertEquals(1, operations.pendingSize());

        stuck.complete(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS));
        assertEquals(1, platform.sent.size(), "exactly one result is delivered");
        assertEquals(0, operations.pendingSize());
    }

    @Test
    void completedOperationIsReplayedWithoutReconnecting() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS)));
        coordinator.handleTransferRequest(defaultRequest(requestId), source);
        assertEquals(1, platform.sent.size());

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, platform.connectAttempts.size(), "replay must not reconnect");
        assertEquals(2, platform.sent.size(), "replay re-sends the cached outcome");
        assertTrue(lastResult().ok());
        assertEquals(1, operations.stats().completedReplays.get());
    }

    @Test
    void failedOperationIsReplayedWithTheSameError() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.failedFuture(new ConnectException("refused")));
        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        DimensionTransferResultMessage replay = lastResult();
        assertFalse(replay.ok());
        assertEquals(TransferError.BACKEND_DOWN, replay.error());
        assertEquals(1, platform.connectAttempts.size());
    }

    @Test
    void sameRequestIdWithDifferentTargetIsRejectedAsConflict() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(new CompletableFuture<>());
        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        coordinator.handleTransferRequest(request("overworld", "end", "minecraft:the_end", requestId), source);

        DimensionTransferResultMessage result = lastResult();
        assertEquals(TransferError.REJECTED, result.error());
        assertEquals(1, operations.stats().conflictingReplays.get());
    }

    @Test
    void sameRequestIdFromDifferentPlayerIsRejectedAsConflict() {
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(new CompletableFuture<>());
        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        UUID otherPlayer = UUID.randomUUID();
        FakeServerConnection otherSource = new FakeServerConnection("overworld", 25_666);
        platform.setCurrentConnection(otherPlayer, otherSource);
        DimensionTransferRequestMessage otherRequest = new DimensionTransferRequestMessage(
                requestId, otherPlayer, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.of("minecraft:the_nether"),
                new Position(0, 64, 0, 0, 0), TransferCause.NETHER_PORTAL);
        coordinator.handleTransferRequest(otherRequest, otherSource);

        DimensionTransferResultMessage result = lastResult();
        assertEquals(TransferError.REJECTED, result.error());
    }

    // --------------------------------------------------------------- abandonment

    @Test
    void disconnectDuringConnectMarksOperationAbandoned() {
        UUID requestId = UUID.randomUUID();
        CompletableFuture<ConnectionRequestBuilder.Result> stuck = new CompletableFuture<>();
        platform.setConnectFuture(stuck);

        coordinator.handleTransferRequest(defaultRequest(requestId), source);
        assertEquals(1, operations.pendingSize());

        assertEquals(1, coordinator.abandonPlayer(PLAYER));
        assertEquals(0, operations.pendingSize());

        // a late success after the disconnect must not emit a result to the dead connection
        stuck.complete(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS));
        assertEquals(0, platform.sent.size());
        assertEquals(0, operations.stats().connectsSucceeded.get());
        assertEquals(TransferOperations.OutcomeKind.ABANDONED, operations.recent(10).get(0).kind());
    }

    // --------------------------------------------------------------- fail-closed

    @Test
    void brokenConfigurationRefusesBeforeAnyConnect() {
        healthy = false;
        UUID requestId = UUID.randomUUID();

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(0, platform.connectAttempts.size(), "no connection attempt may happen on a broken config");
        assertEquals(0, operations.pendingSize());
        DimensionTransferResultMessage result = lastResult();
        assertFalse(result.ok());
        assertEquals(TransferError.UNAVAILABLE, result.error());
        assertEquals(1, operations.stats().refusedBrokenConfig.get());
    }

    // --------------------------------------------------------------- send failure

    @Test
    void resultSendFailureIsCountedNotCrashed() {
        platform.sendPluginMessageResult = false;
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, operations.stats().resultSendFailures.get());
        assertEquals(1, operations.stats().connectsSucceeded.get(), "outcome is still recorded as success");
    }

    @Test
    void resultFallsBackToPiggybackWhenSourceConnectionIsGone() {
        platform.sendPluginMessageResult = false; // direct connection path is dead
        ((kz.bejiihiu.dimensions.velocity.support.FakeRegisteredServer) source.getServer())
                .sendPluginMessageResult = true; // the source backend is still reachable via another player
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, operations.stats().resultSendFallbacks.get());
        assertEquals(0, operations.stats().resultSendFailures.get());
    }

    @Test
    void resultDeliveryFailureOnBothPathsIsCounted() {
        platform.sendPluginMessageResult = false;
        ((kz.bejiihiu.dimensions.velocity.support.FakeRegisteredServer) source.getServer())
                .sendPluginMessageResult = false; // no players on the source backend at all
        UUID requestId = UUID.randomUUID();
        platform.setConnectFuture(CompletableFuture.completedFuture(new FakeResult(ConnectionRequestBuilder.Status.SUCCESS)));

        coordinator.handleTransferRequest(defaultRequest(requestId), source);

        assertEquals(1, operations.stats().resultSendFailures.get());
        assertEquals(0, operations.stats().resultSendFallbacks.get());
    }
}

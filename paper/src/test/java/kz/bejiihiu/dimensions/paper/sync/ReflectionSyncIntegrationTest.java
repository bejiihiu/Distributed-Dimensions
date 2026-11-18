package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reflection bridge, exercised against local test events (no foreign plugins
 * installed): event-name class resolution is covered indirectly by construction;
 * listener registration itself is server-side wiring and out of unit-test scope.
 */
class ReflectionSyncIntegrationTest {

    private static final Predicate<UUID> ALWAYS_ONLINE = uuid -> true;
    private static final Predicate<UUID> ALWAYS_OFFLINE = uuid -> false;

    private final FakeSchedulers schedulers = new FakeSchedulers();

    private ReflectionSyncIntegration integrationFor(Class<?> eventClass, String getterName,
            Predicate<UUID> online, long waitTimeoutMillis) {
        try {
            Method getter = eventClass.getMethod(getterName);
            return new ReflectionSyncIntegration(schedulers, online, waitTimeoutMillis,
                    schedulers::currentMillis, LoggerFactory.getLogger(getClass()),
                    eventClass, getter, eventClass.getName());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void completesWhenConfiguredEventFires() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        assertFalse(callback.firedOnce());

        integration.handleEvent(new TestSyncEvents.UuidPlayerEvent(uuid));

        assertTrue(callback.firedOnce());
        assertEquals(SyncCause.EVENT, callback.ready.get().cause());
        assertEquals(uuid, callback.ready.get().player());
        // no second callback may fire later, even if the timeout ticks by
        schedulers.advanceTicks(200);
        assertTrue(callback.firedOnce());
    }

    @Test
    void completionBeforeTheWaitStillCompletesImmediately() {
        // the completion event can beat awaitSync — the race must not eat the wait
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_ONLINE, 5_000);

        integration.handleEvent(new TestSyncEvents.UuidPlayerEvent(uuid)); // fires with no waiter pending

        CaptureCallback callback = new CaptureCallback();
        integration.waitForSync(uuid, callback);

        assertTrue(callback.firedOnce());
        assertEquals(SyncCause.EVENT, callback.ready.get().cause());
        assertEquals(SyncReadiness.READY, integration.phase());
        assertEquals(0, schedulers.pendingTasks(), "pre-confirmed wait must not arm a timeout");
    }

    @Test
    void huskSyncShapedEventCompletesViaGetUuid() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UserSyncEvent.class, "getUser", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.handleEvent(new TestSyncEvents.UserSyncEvent(uuid));

        assertTrue(callback.firedOnce());
        assertEquals(uuid, callback.ready.get().player());
    }

    @Test
    void playerShapedGetterCompletes() {
        UUID uuid = UUID.randomUUID();
        org.bukkit.entity.Player player = kz.bejiihiu.dimensions.paper.TestPlayers.create(uuid, true);
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.PlayerReturningEvent.class, "getPlayer", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.handleEvent(new TestSyncEvents.PlayerReturningEvent(player));

        assertTrue(callback.firedOnce());
        assertEquals(uuid, callback.ready.get().player());
    }

    @Test
    void unrelatedEventIsIgnored() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.handleEvent(new TestSyncEvents.UnrelatedEvent());

        assertFalse(callback.firedOnce());
    }

    @Test
    void explodingGetterFailsTheWaitAtTheTimeoutInsteadOfThrowing() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.ExplodingGetterEvent.class, "explode", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.handleEvent(new TestSyncEvents.ExplodingGetterEvent());

        assertFalse(callback.firedOnce(), "unextractable events must not complete the wait");
        schedulers.advanceTicks(100); // 5 s = 100 ticks
        assertEquals(TimeoutReason.WAIT_TIMEOUT, callback.timeout.get());
        assertTrue(callback.firedOnce());
    }

    @Test
    void uncoercibleGetterFailsTheWaitAtTheTimeout() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UncoercibleGetterEvent.class, "getAnswer", ALWAYS_ONLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.handleEvent(new TestSyncEvents.UncoercibleGetterEvent());

        assertFalse(callback.firedOnce());
        schedulers.advanceTicks(100);
        assertEquals(TimeoutReason.WAIT_TIMEOUT, callback.timeout.get());
    }

    @Test
    void timeoutReportsOfflinePlayersAsPlayerOffline() {
        UUID uuid = UUID.randomUUID();
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_OFFLINE, 5_000);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        schedulers.advanceTicks(100);

        assertEquals(TimeoutReason.PLAYER_OFFLINE, callback.timeout.get());
        assertTrue(callback.firedOnce());
    }

    @Test
    void eventBoundWaitIsCappedAtTheWaitTimeout() {
        UUID uuid = UUID.randomUUID();
        // 250 ms = 5 ticks — the armed timeout lands there even though nothing else constrains it
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_ONLINE, 250);
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        assertEquals(java.util.List.of("global:5"), schedulers.calls());
        schedulers.advanceTicks(5);
        assertEquals(TimeoutReason.WAIT_TIMEOUT, callback.timeout.get());
    }

    @Test
    void reportHandshakeMatchesTheArmedStrategy() {
        ReflectionSyncIntegration integration = integrationFor(
                TestSyncEvents.UuidPlayerEvent.class, "getPlayer", ALWAYS_ONLINE, 5_000);

        assertEquals("reflection", integration.id());
        assertEquals(SyncReadiness.READY, integration.phase());
        assertTrue(integration.isAvailable());
        assertFalse(integration.describe().isBlank());
    }
}

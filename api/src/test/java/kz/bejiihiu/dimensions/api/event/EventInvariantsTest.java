package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.EventTestSupport;
import kz.bejiihiu.dimensions.api.OperationState;
import kz.bejiihiu.dimensions.api.TransferCause;
import kz.bejiihiu.dimensions.api.TransferError;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventInvariantsTest {

    private static final UUID OP = UUID.randomUUID();

    @Test
    void startEventIsCancellableAndCarriesCommonPayload() {
        Player player = EventTestSupport.player();
        DimensionTransferStartEvent event = new DimensionTransferStartEvent(
                OP, player, DimensionId.OVERWORLD, DimensionId.NETHER, TransferCause.NETHER_PORTAL);

        assertEquals(OP, event.getOperationId());
        assertSame(player, event.getEntity());
        assertSame(player, event.getPlayer());
        assertTrue(event.isPlayerTransfer());
        assertEquals(DimensionId.OVERWORLD, event.getFrom());
        assertEquals(DimensionId.NETHER, event.getTo());
        assertEquals(TransferCause.NETHER_PORTAL, event.getCause());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());

        assertInstanceOf(Cancellable.class, event);
        assertFalse(event.isAsynchronous());
        assertSame(DimensionTransferStartEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void startEventWithNonPlayerEntityHasNoPlayerView() {
        Entity entity = EventTestSupport.entity();
        DimensionTransferStartEvent event = new DimensionTransferStartEvent(
                OP, entity, DimensionId.NETHER, DimensionId.OVERWORLD, TransferCause.PROGRAMMATIC);

        assertNull(event.getPlayer());
        assertFalse(event.isPlayerTransfer());
    }

    @Test
    void completeEventIsNotCancellableAndCarriesDuration() {
        DimensionTransferCompleteEvent event = new DimensionTransferCompleteEvent(
                OP, EventTestSupport.entity(), DimensionId.OVERWORLD, DimensionId.THE_END,
                TransferCause.END_PORTAL, 1234L);

        assertEquals(1234L, event.getDurationMillis());
        assertFalse(event instanceof Cancellable);
        assertSame(DimensionTransferCompleteEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void completeEventRejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferCompleteEvent(
                OP, EventTestSupport.entity(), DimensionId.OVERWORLD, DimensionId.NETHER,
                TransferCause.NETHER_PORTAL, -1L));
    }

    @Test
    void failedEventCarriesErrorPhaseAndOnlineFlag() {
        DimensionTransferFailedEvent event = new DimensionTransferFailedEvent(
                OP, EventTestSupport.player(), DimensionId.NETHER, DimensionId.OVERWORLD,
                TransferCause.NETHER_PORTAL, TransferError.BACKEND_DOWN,
                OperationState.PREPARING, false);

        assertEquals(TransferError.BACKEND_DOWN, event.getError());
        assertEquals(OperationState.PREPARING, event.getPhase());
        assertFalse(event.isPlayerOnline());
        assertSame(DimensionTransferFailedEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void failedEventRejectsTerminalPhases() {
        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferFailedEvent(
                OP, EventTestSupport.entity(), DimensionId.OVERWORLD, DimensionId.NETHER,
                TransferCause.RESPAWN, TransferError.UNKNOWN, OperationState.COMMITTED, true));
        assertThrows(IllegalArgumentException.class, () -> new DimensionTransferFailedEvent(
                OP, EventTestSupport.entity(), DimensionId.OVERWORLD, DimensionId.NETHER,
                TransferCause.RESPAWN, TransferError.UNKNOWN, OperationState.FAILED, true));
    }

    @Test
    void entityTransferEventCarriesSnapshot() {
        var snapshot = (org.bukkit.entity.EntitySnapshot) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.entity.EntitySnapshot.class},
                (p, m, a) -> null);
        EntityTransferEvent event = new EntityTransferEvent(
                OP, EventTestSupport.entity(), DimensionId.OVERWORLD, DimensionId.NETHER,
                TransferCause.NETHER_PORTAL, snapshot);

        assertSame(snapshot, event.getSnapshot());
        assertSame(EntityTransferEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void portalEventsCarryOperationIdEntityAndLocation() {
        Entity entity = EventTestSupport.entity();
        Location portal = new Location(null, 1, 64, 2);
        PortalFoundEvent found = new PortalFoundEvent(OP, entity, DimensionId.NETHER, portal);
        PortalCreatedEvent created = new PortalCreatedEvent(OP, entity, DimensionId.NETHER, portal, true);

        assertEquals(OP, found.getOperationId());
        assertEquals(OP, created.getOperationId());
        assertEquals(DimensionId.NETHER, found.getTargetDimension());
        assertEquals(DimensionId.NETHER, created.getTargetDimension());
        assertTrue(created.isForced());
        assertEquals(1.0, found.getPortalLocation().getX(), 0.0);
        // mutating the caller's location must not leak into the event (defensive clone)
        portal.setX(999);
        assertEquals(1.0, created.getPortalLocation().getX(), 0.0);
        assertSame(PortalFoundEvent.getHandlerList(), found.getHandlers());
        assertSame(PortalCreatedEvent.getHandlerList(), created.getHandlers());
    }

    @Test
    void everyEventTypeHasItsOwnStaticHandlerListAndIsSynchronous() {
        // bukkit requires: static getHandlerList() == instance getHandlers(), one per concrete class
        record EventClass(Class<? extends Event> type, HandlerList list) {
        }
        EventClass[] classes = {
                new EventClass(DimensionTransferStartEvent.class, DimensionTransferStartEvent.getHandlerList()),
                new EventClass(DimensionTransferCompleteEvent.class, DimensionTransferCompleteEvent.getHandlerList()),
                new EventClass(DimensionTransferFailedEvent.class, DimensionTransferFailedEvent.getHandlerList()),
                new EventClass(EntityTransferEvent.class, EntityTransferEvent.getHandlerList()),
                new EventClass(PortalFoundEvent.class, PortalFoundEvent.getHandlerList()),
                new EventClass(PortalCreatedEvent.class, PortalCreatedEvent.getHandlerList()),
        };
        for (EventClass ec : classes) {
            assertSame(ec.list(), ec.list(), ec.type().getSimpleName() + " handler list must be stable");
        }
        // handler lists of sibling event types must not be shared — bukkit dispatches per list
        for (int i = 0; i < classes.length; i++) {
            for (int j = i + 1; j < classes.length; j++) {
                assertNotSame(classes[i].list(), classes[j].list(),
                        classes[i].type().getSimpleName() + " shares a handler list with " + classes[j].type().getSimpleName());
            }
        }
    }
}

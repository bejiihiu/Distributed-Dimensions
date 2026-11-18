package kz.bejiihiu.dimensions.paper.chunktickets;

import kz.bejiihiu.dimensions.paper.TestWorlds;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;

import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refresh/expiry/bookkeeping behaviour of {@link ChunkTicketService}, driven by the
 * fake schedulers with a manual clock. No world state is touched; the world is an
 * identity-only proxy and the gateway is a recorder.
 */
class ChunkTicketServiceTest {

    private static final long TTL = ChunkTicketService.TTL_TICKS;

    private FakeSchedulers schedulers;
    private FakeChunkTicketGateway gateway;
    private List<String> diagnostics;
    private World world;
    private String worldKey;

    @BeforeEach
    void setUp() {
        schedulers = new FakeSchedulers();
        gateway = new FakeChunkTicketGateway();
        diagnostics = new java.util.ArrayList<>();
        world = TestWorlds.create("test-world");
        worldKey = world.getUID().toString();
    }

    private ChunkTicketService service(boolean enabled, boolean ring) {
        return new ChunkTicketService(schedulers, gateway, schedulers::currentTick,
                enabled, ring, diagnostics::add);
    }

    private void drain() {
        schedulers.advanceTicks(1);
    }

    private static TicketKey key(String worldKey, int x, int z) {
        return new TicketKey(worldKey, x, z);
    }

    @Test
    void addTicketCreatesSingleTicketWhenRingDisabled() {
        ChunkTicketService service = service(true, false);
        service.addTicket(world, 3, -2);
        drain();
        assertEquals(List.of(key(worldKey, 3, -2)), gateway.adds());
        assertEquals(List.of("region:3,-2", "region:3,-2:300"), schedulers.calls());
        assertEquals(1, service.trackedCount());
    }

    @Test
    void addTicketWithRingAddsEightNeighboursAroundCenter() {
        ChunkTicketService service = service(true, true);
        service.addTicket(world, 0, 0);
        drain();
        assertEquals(9, gateway.adds().size());
        assertEquals(9, service.trackedCount());
        assertTrue(gateway.adds().contains(key(worldKey, 0, 0)));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                assertTrue(gateway.adds().contains(key(worldKey, dx, dz)),
                        "ring is missing " + dx + "," + dz);
            }
        }
    }

    @Test
    void ticketExpiresExactlyTtlAfterTouch() {
        ChunkTicketService service = service(true, false);
        service.addTicket(world, 7, 7);
        drain();
        schedulers.advanceTicks(TTL - 2);
        assertTrue(gateway.removes().isEmpty(), "ticket must survive until the TTL elapses");
        schedulers.advanceTicks(1);
        assertEquals(List.of(key(worldKey, 7, 7)), gateway.removes());
        assertEquals(0, service.trackedCount());
    }

    @Test
    void refreshOnTouchPushesExpiryForward() {
        ChunkTicketService service = service(true, false);
        service.addTicket(world, 7, 7);
        drain();
        // 200 ticks in, refresh the chunk (e.g. another entity transfer touched it)
        schedulers.advanceTicks(200);
        service.addTicket(world, 7, 7);
        drain();
        // 200 more ticks (400 total): without the refresh the ticket would have expired at 300
        schedulers.advanceTicks(200);
        assertTrue(gateway.removes().isEmpty(), "refreshed ticket must outlive its original TTL");
        // 300 ticks after the refresh: now it expires
        schedulers.advanceTicks(100);
        assertEquals(List.of(key(worldKey, 7, 7)), gateway.removes());
        assertEquals(0, service.trackedCount());
    }

    @Test
    void refreshedTicketCancelsStaleRemovalTask() {
        ChunkTicketService service = service(true, false);
        service.addTicket(world, 1, 1);
        drain();
        service.addTicket(world, 1, 1);
        drain();
        // The re-armed removal must be the only surviving timer; advancing past the
        // first timer's due time must not remove the ticket twice.
        schedulers.advanceTicks(TTL);
        assertEquals(1, gateway.removes().size());
    }

    @Test
    void ringEntriesExpireIndependently() {
        ChunkTicketService service = service(true, true);
        service.addTicket(world, 0, 0);
        drain();
        schedulers.advanceTicks(TTL - 2);
        assertTrue(gateway.removes().isEmpty());
        schedulers.advanceTicks(1);
        assertEquals(9, gateway.removes().size());
        assertEquals(0, service.trackedCount());
    }

    @Test
    void disabledServiceDoesNothing() {
        ChunkTicketService service = service(false, true);
        service.addTicket(world, 0, 0);
        schedulers.advanceTicks(TTL + 10);
        assertTrue(gateway.adds().isEmpty());
        assertTrue(gateway.removes().isEmpty());
        assertEquals(0, service.trackedCount());
        assertEquals(0, schedulers.pendingTasks());
    }

    @Test
    void addTicketForBlockConvertsWithFloorDivision() {
        ChunkTicketService service = service(true, false);
        service.addTicketForBlock(world, -1, -17);
        drain();
        assertEquals(List.of(key(worldKey, -1, -2)), gateway.adds());
    }

    @Test
    void gatewayFailureIsReportedNotSwallowed() {
        gateway.setAddResult(false);
        ChunkTicketService service = service(true, false);
        service.addTicket(world, 1, 1);
        drain();
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("could not apply plugin ticket"));
    }

    @Test
    void closeRemovesAllTrackedTicketsAndCancelsTimers() {
        ChunkTicketService service = service(true, true);
        service.addTicket(world, 0, 0);
        drain();
        service.close();
        assertEquals(9, gateway.removes().size());
        assertEquals(0, service.trackedCount());
        // nothing may fire afterwards
        schedulers.advanceTicks(TTL * 2);
        assertEquals(9, gateway.removes().size());
    }

    @Test
    void uuidOfProxyIsStableAndDistinctPerName() {
        World other = TestWorlds.create("other-world");
        org.junit.jupiter.api.Assertions.assertNotEquals(world.getUID(), other.getUID());
        org.junit.jupiter.api.Assertions.assertEquals(
                UUID.nameUUIDFromBytes("test-world".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                world.getUID());
    }
}

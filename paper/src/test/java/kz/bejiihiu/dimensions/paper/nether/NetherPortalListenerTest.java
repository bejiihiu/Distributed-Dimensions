package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.paper.FakePlayers;
import kz.bejiihiu.dimensions.paper.TestWorlds;
import kz.bejiihiu.dimensions.paper.transfer.FakeTransferBackend;
import kz.bejiihiu.dimensions.paper.transfer.PortalTransferService;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherPortalListenerTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private PortalTransferService service;
    private NetherPortalListener listener;

    private World overworld, nether, theEnd;

    @BeforeEach
    void setUp() {
        NetherCrossingMarker.clear();
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        service = new PortalTransferService(backend, java.time.Clock.fixed(T0, ZoneOffset.UTC));
        listener = new NetherPortalListener(backend, service);
        overworld = TestWorlds.create("world", World.Environment.NORMAL);
        nether = TestWorlds.create("world_nether", World.Environment.NETHER);
        theEnd = TestWorlds.create("world_the_end", World.Environment.THE_END);
    }

    private Player fakePlayer(World world) {
        return FakePlayers.create(UUID.randomUUID(), new Location(world, 0.0, 64.0, 0.0, 90.0f, 0.0f), true);
    }

    private PlayerPortalEvent portalEvent(Player player, World portalWorld,
                                          double x, double y, double z,
                                          PlayerTeleportEvent.TeleportCause cause) {
        Location from = new Location(portalWorld, x, y, z, 90.0f, 0.0f);
        Location to = new Location(portalWorld, x, y, z, 90.0f, 0.0f);
        return new PlayerPortalEvent(player, from, to, cause);
    }

    @Test
    void overworldPortalTriggersNetherTransferAndIsCancelled() {
        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, 100.0, 64.0, -200.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled(), "the portal event must never stand — vanilla cannot switch servers");
        assertEquals(1, backend.channelFake().sent().size());
        assertEquals(1, backend.eventRecorder().starts().size());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).from());
        assertEquals(DimensionId.NETHER, backend.eventRecorder().starts().get(0).to());
        assertEquals(TransferCause.NETHER_PORTAL, backend.eventRecorder().starts().get(0).cause());
        // converter ran: floor(100/8)=12, floor(-200/8)=-25, Y kept, yaw/pitch kept
        Position row = backend.journalFake().rows().get(0).targetPosition();
        assertEquals(12.0, row.x());
        assertEquals(64.0, row.y());
        assertEquals(-25.0, row.z());
        assertEquals(90.0, row.yaw());
    }

    @Test
    void netherPortalTriggersOverworldTransfer() {
        Player player = fakePlayer(nether);
        PlayerPortalEvent event = portalEvent(player, nether, 12.0, 70.0, -25.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.NETHER, backend.eventRecorder().starts().get(0).from());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).to());
        // nether → overworld scales by 8, no floor
        Position row = backend.journalFake().rows().get(0).targetPosition();
        assertEquals(96.0, row.x());
        assertEquals(70.0, row.y());
        assertEquals(-200.0, row.z());
    }

    @Test
    void endEnvironmentIsNotHandled() {
        Player player = fakePlayer(theEnd);
        PlayerPortalEvent event = portalEvent(player, theEnd, 0.0, 64.0, 0.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "a nether portal in the end is out of scope");
        assertTrue(backend.channelFake().sent().isEmpty());
    }

    @Test
    void endPortalCauseIsNotHandled() {
        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, 0.0, 64.0, 0.0,
                PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "the end wave owns END_PORTAL completions");
        assertTrue(backend.channelFake().sent().isEmpty());
    }

    @Test
    void duplicateCompletionWithinTheDedupeWindowIsSuppressed() {
        Player player = fakePlayer(overworld);
        PlayerPortalEvent first = portalEvent(player, overworld, 100.0, 64.0, -200.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        PlayerPortalEvent second = portalEvent(player, overworld, 100.0, 64.0, -200.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(first);
        listener.onPortal(second);

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled(), "even a suppressed duplicate keeps vanilla vetoed");
        assertEquals(1, backend.channelFake().sent().size(), "one dwell, one cross-server transfer");
    }

    @Test
    void degradedBackendRefusesWithMessage() {
        FakeTransferBackend degraded = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir), false);
        PortalTransferService failing =
                new PortalTransferService(degraded, java.time.Clock.fixed(T0, ZoneOffset.UTC));
        NetherPortalListener degradedListener = new NetherPortalListener(degraded, failing);

        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, 0.0, 64.0, 0.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        degradedListener.onPortal(event);

        assertTrue(event.isCancelled());
        assertTrue(degraded.channelFake().sent().isEmpty());
        assertTrue(FakePlayers.messages(player).stream().anyMatch(m -> m.contains("cannot travel")));
    }

    @Test
    void disabledNetherPortalsCancelAndRefuse() {
        var cfg = backend.config();
        var disabled = new kz.bejiihiu.dimensions.common.config.PluginConfig(
                cfg.configVersion(), cfg.general(), cfg.network(),
                new kz.bejiihiu.dimensions.common.config.PortalsSettings(
                        new kz.bejiihiu.dimensions.common.config.NetherPortalsSettings(false, true, true, true, true),
                        cfg.portals().end(), cfg.portals().gateway()),
                cfg.entities(), cfg.api(), cfg.sync(), cfg.redis(), cfg.database(), cfg.debug(), cfg.mirage());
        FakeTransferBackend disabledBackend = new FakeTransferBackend(disabled);
        PortalTransferService disabledService =
                new PortalTransferService(disabledBackend, java.time.Clock.fixed(T0, ZoneOffset.UTC));
        NetherPortalListener disabledListener = new NetherPortalListener(disabledBackend, disabledService);

        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, 0.0, 64.0, 0.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        disabledListener.onPortal(event);

        assertTrue(event.isCancelled());
        assertTrue(disabledBackend.channelFake().sent().isEmpty());
        assertTrue(FakePlayers.messages(player).stream().anyMatch(m -> m.contains("cannot travel")));
    }

    @Test
    void conversionIsFlooredForNegativeCoordinates() {
        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, -29.0, 64.0, -81.0,
                PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);

        listener.onPortal(event);

        Position row = backend.journalFake().rows().get(0).targetPosition();
        // Math.floor(-29 / 8) == -4 (not -3 — floor, never truncation toward zero)
        assertEquals(-4.0, row.x());
        assertEquals(-11.0, row.z());
    }
}

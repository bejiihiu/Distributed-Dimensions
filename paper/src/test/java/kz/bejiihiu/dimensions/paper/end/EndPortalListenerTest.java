package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portal-event classification and exit-destination resolution: END_PORTAL in the overworld
 * (entry) vs in The End (exit), bed/anchor routing, and the fail-closed refuse paths.
 */
class EndPortalListenerTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private PortalTransferService service;
    private EndPortalListener listener;

    private World overworld, theEnd, nether;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        service = new PortalTransferService(backend, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        listener = new EndPortalListener(backend, service);
        overworld = TestWorlds.create("world", World.Environment.NORMAL);
        theEnd = TestWorlds.create("world_the_end", World.Environment.THE_END);
        nether = TestWorlds.create("world_nether", World.Environment.NETHER);
    }

    private Player fakePlayer(World world) {
        return FakePlayers.create(UUID.randomUUID(), new Location(world, 10.0, 64.0, 10.0, 90.0f, 0.0f), true);
    }

    private static PlayerPortalEvent portalEvent(Player player, World portalWorld,
                                                 PlayerTeleportEvent.TeleportCause cause) {
        Location from = new Location(portalWorld, 100.0, 64.0, -200.0, 90.0f, 0.0f);
        Location to = new Location(portalWorld, 100.0, 64.0, -200.0, 90.0f, 0.0f);
        return new PlayerPortalEvent(player, from, to, cause);
    }

    @Test
    void overworldEndPortalTriggersEntryTransferAndIsCancelled() {
        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled(), "the portal event must never stand — vanilla cannot switch servers");
        assertEquals(1, backend.channelFake().sent().size());
        assertEquals(1, backend.eventRecorder().starts().size());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).from());
        assertEquals(DimensionId.THE_END, backend.eventRecorder().starts().get(0).to());
        assertEquals(TransferCause.END_PORTAL, backend.eventRecorder().starts().get(0).cause());
        assertEquals("end", onlyRowTargetServer());
    }

    @Test
    void endExitPortalWithBedRecordRoutesToBedBackend() {
        Player player = fakePlayer(theEnd);
        backend.respawnStore().upsert(new RespawnPointRecord(player.getUniqueId(),
                DimensionId.OVERWORLD, "world",
                new Position(10.5, 64.0, 11.5, 90.0, 0.0), RespawnKind.BED, T0));
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).to());
        assertEquals("overworld", onlyRowTargetServer());
        assertEquals(new Position(10.5, 64.0, 11.5, 90.0, 0.0), onlyRowPosition());
    }

    @Test
    void endExitPortalWithAnchorRecordRoutesToNetherBackend() {
        Player player = fakePlayer(theEnd);
        Position anchorPos = new Position(-100.5, 70.0, 200.5, 180.0, 0.0);
        backend.respawnStore().upsert(new RespawnPointRecord(player.getUniqueId(),
                DimensionId.NETHER, "world_nether",
                anchorPos, RespawnKind.RESPAWN_ANCHOR, T0));
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.NETHER, backend.eventRecorder().starts().get(0).to());
        assertEquals("nether", onlyRowTargetServer());
        assertEquals(anchorPos, onlyRowPosition());
    }

    @Test
    void endExitPortalWithoutRecordRoutesToOverworldWorldSpawn() {
        Player player = fakePlayer(theEnd);
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).to());
        assertEquals("overworld", onlyRowTargetServer());
        // the journal carries the portal position; the target resolves world spawn placement
        Position recorded = onlyRowPosition();
        assertEquals(100.0, recorded.x());
        assertEquals(-200.0, recorded.z());
    }

    @Test
    void endExitPortalWithUnroutableRecordDimensionFallsBackToOverworld() {
        Player player = fakePlayer(theEnd);
        backend.respawnStore().upsert(new RespawnPointRecord(player.getUniqueId(),
                DimensionId.of("mymod:fragments/void"), "custom_world",
                new Position(10.5, 64.0, 11.5, 0.0, 0.0), RespawnKind.BED, T0));
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).to());
        assertEquals("overworld", onlyRowTargetServer());
    }

    @Test
    void endExitPortalWithUnreadableStoreFallsBackToOverworld() {
        Player player = fakePlayer(theEnd);
        backend.setRespawnPoints(new kz.bejiihiu.dimensions.common.persistence.RespawnPointStore() {
            @Override public void upsert(RespawnPointRecord point) { }
            @Override public java.util.Optional<RespawnPointRecord> find(UUID player) {
                throw new kz.bejiihiu.dimensions.common.persistence.PersistenceException("store down (test)");
            }
            @Override public boolean delete(UUID player) { return false; }
        });
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertTrue(event.isCancelled());
        assertEquals(DimensionId.OVERWORLD, backend.eventRecorder().starts().get(0).to());
        assertEquals("overworld", onlyRowTargetServer());
    }

    @Test
    void netherEnvironmentIsNotHandled() {
        Player player = fakePlayer(nether);
        PlayerPortalEvent event = portalEvent(player, nether, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "not our portal — a nether-side end portal is out of scope");
        assertTrue(backend.channelFake().sent().isEmpty());
    }

    @Test
    void gatewayTeleportCauseIsNotHandled() {
        Player player = fakePlayer(theEnd);
        PlayerPortalEvent event = portalEvent(player, theEnd, PlayerTeleportEvent.TeleportCause.END_GATEWAY);

        listener.onPortal(event);

        assertFalse(event.isCancelled(), "end gateways are intra-End and native — untouched");
        assertTrue(backend.channelFake().sent().isEmpty());
    }

    @Test
    void disabledEndPortalsRefuseWithoutDispatch() {
        // build a config with portals.end.enabled = false
        var cfg = backend.config();
        var disabled = new kz.bejiihiu.dimensions.common.config.PluginConfig(
                cfg.configVersion(), cfg.general(), cfg.network(),
                new kz.bejiihiu.dimensions.common.config.PortalsSettings(
                        cfg.portals().nether(),
                        new kz.bejiihiu.dimensions.common.config.EndPortalsSettings(false),
                        cfg.portals().gateway()),
                cfg.entities(), cfg.api(), cfg.sync(), cfg.redis(), cfg.database(), cfg.debug(), cfg.mirage());
        FakeTransferBackend disabledBackend = new FakeTransferBackend(disabled);
        PortalTransferService disabledService =
                new PortalTransferService(disabledBackend, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        EndPortalListener disabledListener = new EndPortalListener(disabledBackend, disabledService);

        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        disabledListener.onPortal(event);

        assertTrue(event.isCancelled());
        assertTrue(disabledBackend.channelFake().sent().isEmpty());
        assertTrue(disabledBackend.eventRecorder().starts().isEmpty());
        assertTrue(FakePlayers.messages(player).stream().anyMatch(m -> m.contains("disabled")));
    }

    @Test
    void degradedBackendRefusesWithClearMessage() {
        FakeTransferBackend degraded = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir), false);
        PortalTransferService failing =
                new PortalTransferService(degraded, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        EndPortalListener degradedListener = new EndPortalListener(degraded, failing);

        Player player = fakePlayer(overworld);
        PlayerPortalEvent event = portalEvent(player, overworld, PlayerTeleportEvent.TeleportCause.END_PORTAL);

        degradedListener.onPortal(event);

        assertTrue(event.isCancelled());
        assertTrue(degraded.channelFake().sent().isEmpty());
        assertTrue(FakePlayers.messages(player).stream().anyMatch(m -> m.contains("cannot travel")));
    }

    private String onlyRowTargetServer() {
        return backend.journalFake().rows().get(0).targetServer();
    }

    private Position onlyRowPosition() {
        return backend.journalFake().rows().get(0).targetPosition();
    }
}


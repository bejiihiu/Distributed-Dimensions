package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.FakePlayers;
import kz.bejiihiu.dimensions.paper.TestWorlds;
import kz.bejiihiu.dimensions.paper.transfer.FakeTransferBackend;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorder: {@code PlayerSetSpawnEvent} with cause BED/RESPAWN_ANCHOR upserts the
 * respawn record; other causes and unroutable worlds are ignored; a cleared spawn removes
 * the record.
 */
class RespawnRecorderTest {

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private RespawnRecorder recorder;

    private World overworld, nether, theEnd, custom;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        recorder = new RespawnRecorder(backend);
        overworld = TestWorlds.create("world", World.Environment.NORMAL);
        nether = TestWorlds.create("world_nether", World.Environment.NETHER);
        theEnd = TestWorlds.create("world_the_end", World.Environment.THE_END);
        custom = TestWorlds.create("custom_world", World.Environment.CUSTOM);
    }

    private Player fakePlayer() {
        return FakePlayers.create(UUID.randomUUID(),
                new Location(overworld, 1.0, 64.0, 1.0, 0.0f, 0.0f), true);
    }

    private void fire(Player player, PlayerSetSpawnEvent.Cause cause, Location location) {
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player, cause, location, false, false, null);
        recorder.onSetSpawn(event);
        backend.fakeSchedulers().advanceMillis(0);   // flush the async upsert
    }

    @Test
    void bedSetSpawnUpsertsBedRecord() {
        Player player = fakePlayer();
        Location bed = new Location(overworld, 10.5, 64.0, 11.5, 90.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.BED, bed);

        Optional<RespawnPointRecord> record = backend.respawnStore().find(player.getUniqueId());
        assertTrue(record.isPresent());
        assertEquals(RespawnKind.BED, record.get().kind());
        assertEquals(DimensionId.OVERWORLD, record.get().dimension());
        assertEquals("world", record.get().world());
        assertEquals(10.5, record.get().position().x());
    }

    @Test
    void anchorSetSpawnUpsertsAnchorRecord() {
        Player player = fakePlayer();
        Location anchor = new Location(nether, -100.5, 70.0, 200.5, 180.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR, anchor);

        RespawnPointRecord record = backend.respawnStore().find(player.getUniqueId()).orElseThrow();
        assertEquals(RespawnKind.RESPAWN_ANCHOR, record.kind());
        assertEquals(DimensionId.NETHER, record.dimension());
        assertEquals("world_nether", record.world());
    }

    @Test
    void commandSetSpawnIsNotRecorded() {
        Player player = fakePlayer();
        Location spot = new Location(overworld, 50.0, 64.0, 50.0, 0.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.COMMAND, spot);

        assertTrue(backend.respawnStore().find(player.getUniqueId()).isEmpty());
    }

    @Test
    void pluginSetSpawnIsNotRecorded() {
        Player player = fakePlayer();
        Location spot = new Location(overworld, 50.0, 64.0, 50.0, 0.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.PLUGIN, spot);

        assertTrue(backend.respawnStore().find(player.getUniqueId()).isEmpty());
    }

    @Test
    void clearedSpawnDeletesTheRecord() {
        Player player = fakePlayer();
        backend.respawnStore().upsert(new RespawnPointRecord(player.getUniqueId(),
                DimensionId.OVERWORLD, "world",
                new kz.bejiihiu.dimensions.common.model.Position(10.5, 64.0, 11.5), RespawnKind.BED,
                java.time.Instant.now()));

        fire(player, PlayerSetSpawnEvent.Cause.BED, null);

        assertTrue(backend.respawnStore().find(player.getUniqueId()).isEmpty());
    }

    @Test
    void customDimensionBedsAreNotTracked() {
        Player player = fakePlayer();
        Location bed = new Location(custom, 10.5, 64.0, 11.5, 0.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.BED, bed);

        assertTrue(backend.respawnStore().find(player.getUniqueId()).isEmpty());
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("not tracked")));
    }

    @Test
    void recordsAreReplacedOnResleep() {
        Player player = fakePlayer();
        Location first = new Location(overworld, 10.5, 64.0, 11.5, 90.0f, 0.0f);
        Location second = new Location(nether, -100.5, 70.0, 200.5, 180.0f, 0.0f);

        fire(player, PlayerSetSpawnEvent.Cause.BED, first);
        fire(player, PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR, second);

        RespawnPointRecord record = backend.respawnStore().find(player.getUniqueId()).orElseThrow();
        assertEquals(RespawnKind.RESPAWN_ANCHOR, record.kind());
        assertEquals(DimensionId.NETHER, record.dimension());
    }
}



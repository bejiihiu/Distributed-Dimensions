package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.FakeBlockWorld;
import kz.bejiihiu.dimensions.paper.transfer.FakeTransferBackend;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Missing-respawn-block detection: the recorded bed/anchor must still exist (and be
 * charged, for anchors) or the exit portal falls back to world spawn.
 */
class BedAnchorValidatorTest {

    @TempDir
    Path tempDir;

    private FakeBlockWorld bedWorld;
    private FakeTransferBackend backend;
    private BedAnchorValidator validator;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        bedWorld = FakeBlockWorld.create("world", World.Environment.NORMAL);
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        validator = new BedAnchorValidator(backend);
        playerUuid = UUID.randomUUID();
    }

    private void seedRecord(RespawnKind kind, int x, int y, int z) {
        backend.respawnStore().upsert(new RespawnPointRecord(playerUuid,
                kind == RespawnKind.BED ? DimensionId.OVERWORLD : DimensionId.NETHER,
                kind == RespawnKind.BED ? "world" : "world_nether",
                new Position(x + 0.5, y, z + 0.5, 0.0, 0.0), kind, Instant.now()));
    }

    private boolean validAt(int x, int y, int z) {
        return validator.isValid(bedWorld.world(), new Position(x + 0.5, y, z + 0.5, 0.0, 0.0), playerUuid);
    }

    @Test
    void noRecordMeansValid() {
        assertTrue(validAt(10, 64, 10));
    }

    @Test
    void bedPresentAtRecordedPositionIsValid() {
        seedRecord(RespawnKind.BED, 10, 64, 10);
        bedWorld.setBlock(10, 64, 10, Material.WHITE_BED);
        assertTrue(validAt(10, 64, 10));
    }

    @Test
    void bedPresentAsNeighbourIsValid() {
        seedRecord(RespawnKind.BED, 10, 64, 10);
        // recorded position is the head block; the foot lies in a horizontal neighbour
        bedWorld.setBlock(11, 64, 10, Material.LIGHT_GRAY_BED);
        assertTrue(validAt(10, 64, 10));
    }

    @Test
    void missingBedFallsBackToInvalid() {
        seedRecord(RespawnKind.BED, 10, 64, 10);   // world has no bed anywhere
        assertFalse(validAt(10, 64, 10));
    }

    @Test
    void replacedBedBlockIsInvalid() {
        seedRecord(RespawnKind.BED, 10, 64, 10);
        bedWorld.setBlock(10, 64, 10, Material.DIRT);
        assertFalse(validAt(10, 64, 10));
    }

    @Test
    void chargedAnchorIsValid() {
        seedRecord(RespawnKind.RESPAWN_ANCHOR, -20, 70, 30);
        bedWorld.setBlock(-20, 70, 30, Material.RESPAWN_ANCHOR).setCharges(-20, 70, 30, 2);
        assertTrue(validAt(-20, 70, 30));
    }

    @Test
    void unchargedAnchorIsInvalid() {
        seedRecord(RespawnKind.RESPAWN_ANCHOR, -20, 70, 30);
        bedWorld.setBlock(-20, 70, 30, Material.RESPAWN_ANCHOR).setCharges(-20, 70, 30, 0);
        assertFalse(validAt(-20, 70, 30));
    }

    @Test
    void missingAnchorIsInvalid() {
        seedRecord(RespawnKind.RESPAWN_ANCHOR, -20, 70, 30);
        assertFalse(validAt(-20, 70, 30));
    }
}


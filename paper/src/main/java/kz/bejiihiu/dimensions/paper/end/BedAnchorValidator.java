package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.transfer.PositionValidator;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.Material;

import java.util.Optional;
import java.util.UUID;

/**
 * Missing-respawn-block detection for the End exit portal, vanilla semantics:
 *
 * <ul>
 *   <li><b>Bed</b> — at least one bed block in the recorded position or one of its
 *       orthogonal neighbours (covers head/foot reorientation since the spawn block is
 *       the head's block position). A bed that is gone — exploded, broken, replaced by
 *       another block — means the recorded spawn is dead and the player goes to world
 *       spawn instead.</li>
 *   <li><b>Respawn anchor</b> — the recorded position (or a neighbour) holds a respawn
 *       anchor with at least one charge. An uncharged or missing anchor also falls back
 *       to world spawn.</li>
 * </ul>
 *
 * <p>Positions without a respawn record are plain portal targets and always pass.
 */
public final class BedAnchorValidator implements PositionValidator {

    /** Offsets covering the recorded block and its six face neighbours. */
    private static final int[][] NEIGHBOURS = {
            {0, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
    };

    private final TransferBackend backend;

    public BedAnchorValidator(TransferBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    @Override
    public boolean isValid(World world, Position position, UUID playerUuid) {
        Optional<RespawnPointRecord> record;
        try {
            record = backend.respawnPoints().find(playerUuid);
        } catch (RuntimeException e) {
            // cannot prove the block is fine — be conservative: treat as unusable and let
            // the caller fall back to world spawn (vanilla missing-block semantics)
            backend.diagnostics().accept("respawn store unreadable during arrival validation for player "
                    + playerUuid + ": " + e.getMessage());
            return false;
        }
        if (record.isEmpty()) {
            return true;
        }
        RespawnKind kind = record.get().kind();
        return switch (kind) {
            case BED -> hasBedNearby(world, floor(position.x()), floor(position.y()), floor(position.z()));
            case RESPAWN_ANCHOR -> hasChargedAnchorNearby(world, floor(position.x()), floor(position.y()), floor(position.z()));
            default -> true;
        };
    }

    private boolean hasBedNearby(World world, int x, int y, int z) {
        for (int[] offset : NEIGHBOURS) {
            Material type = world.getBlockAt(x + offset[0], y + offset[1], z + offset[2]).getType();
            if (isBed(type)) {
                return true;
            }
        }
        return false;
    }

    // every vanilla bed material ends with _BED (WHITE_BED .. BLACK_BED); org.bukkit.Tag would be
    // the API-pure check but is initialised from the server registry and cannot survive a unit test
    private static boolean isBed(Material material) {
        return material != null && material.name().endsWith("_BED");
    }

    private boolean hasChargedAnchorNearby(World world, int x, int y, int z) {
        for (int[] offset : NEIGHBOURS) {
            Block block = world.getBlockAt(x + offset[0], y + offset[1], z + offset[2]);
            if (block.getType() == Material.RESPAWN_ANCHOR) {
                if (block.getBlockData() instanceof RespawnAnchor anchor && anchor.getCharges() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}

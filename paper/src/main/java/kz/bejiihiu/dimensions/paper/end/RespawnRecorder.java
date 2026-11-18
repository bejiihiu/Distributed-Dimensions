package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Instant;
import java.util.UUID;

/**
 * Records the player's bed / respawn-anchor respawn points into the shared store — the
 * data the End exit portal routes by.
 *
 * <p>Paper fires {@code PlayerSetSpawnEvent} whenever a player's spawn point is set, with
 * a cause ({@code BED} / {@code RESPAWN_ANCHOR} / {@code COMMAND} / {@code PLUGIN} /
 * {@code PLAYER_RESPAWN} / {@code UNKNOWN}). We observe only the two vanilla
 * block-interaction causes — the {@code /spawnpoint} command and plugin-driven changes
 * are deliberately not tracked (an arbitrary command-set spawn is not a bed/anchor and
 * must not be validated as one).
 *
 * <p>Costs and semantics:
 *
 * <ul>
 *   <li>Fires once per successful interaction — cheaper than sleep-start events and
 *       covers anchors uniformly. This is the Paper-documented choke point and is
 *       preferred over {@code PlayerBedEnterEvent} / interact-sniffing.</li>
 *   <li>We never cancel or modify the event; we record after the fact.</li>
 *   <li>The record write is handed to the async scheduler — setting a spawn never blocks
 *       a region thread on database IO.</li>
 *   <li>Respawn records are only kept for the three vanilla environments. A bed in a
 *       custom-dimension world is not tracked: there is no backend route for it and
 *       recording one would silently misroute the exit portal.</li>
 *   <li>A cleared spawn ({@code getLocation() == null}) removes the record — vanilla
 *       semantics ("missing respawn point" -&gt; world spawn) apply to the exit portal
 *       again.</li>
 * </ul>
 */
public final class RespawnRecorder implements Listener {

    private final TransferBackend backend;

    public RespawnRecorder(TransferBackend backend) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        RespawnKind kind = kindOf(event.getCause());
        if (kind == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Location location = event.getLocation();

        if (location == null) {
            backend.schedulers().runAsync(() -> {
                try {
                    backend.respawnPoints().delete(playerUuid);
                } catch (RuntimeException e) {
                    backend.diagnostics().accept("failed to delete respawn point for player "
                            + playerUuid + ": " + e.getMessage());
                }
            });
            return;
        }

        World world = location.getWorld();
        DimensionId dimension = dimensionOf(world);
        if (dimension == null) {
            backend.diagnostics().accept("respawn point in world '" + world.getName()
                    + "' (environment " + world.getEnvironment()
                    + ") is not tracked - no route for it exists");
            return;
        }

        RespawnPointRecord record = new RespawnPointRecord(
                playerUuid, dimension, world.getName(),
                new Position(location.getX(), location.getY(), location.getZ(),
                        location.getYaw(), location.getPitch()),
                kind, Instant.now());
        backend.schedulers().runAsync(() -> {
            try {
                backend.respawnPoints().upsert(record);
            } catch (RuntimeException e) {
                backend.diagnostics().accept("failed to record respawn point for player "
                        + playerUuid + ": " + e.getMessage());
            }
        });
    }

    private static RespawnKind kindOf(PlayerSetSpawnEvent.Cause cause) {
        return switch (cause) {
            case BED -> RespawnKind.BED;
            case RESPAWN_ANCHOR -> RespawnKind.RESPAWN_ANCHOR;
            default -> null;
        };
    }

    private static DimensionId dimensionOf(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> DimensionId.OVERWORLD;
            case NETHER -> DimensionId.NETHER;
            case THE_END -> DimensionId.THE_END;
            default -> null;
        };
    }
}

package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.interop.CrossServerTransfer;
import kz.bejiihiu.dimensions.interop.TransferCommand;
import kz.bejiihiu.dimensions.paper.nether.NetherCrossingMarker;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * The mirage guards: every way a player or entity can end up inside a mirage is
 * intercepted here.
 *
 * <table>
 *   <caption>Guard table (event → behavior)</caption>
 *   <tr><th>Event</th><th>Priority</th><th>Action</th></tr>
 *   <tr><td>{@link PlayerTeleportEvent}</td><td>HIGH, ignoreCancelled=false</td>
 *       <td>cancel + cross-server transfer (cause {@code MIRAGE_TELEPORT}); players only</td></tr>
 *   <tr><td>{@link EntityTeleportEvent}</td><td>HIGH, ignoreCancelled=false</td>
 *       <td>cancel + metric + debug log; entity transfers do not exist yet (Phase 5) — the
 *           entity stays put. Never auto-relocated</td></tr>
 *   <tr><td>{@link PlayerRespawnEvent}</td><td>HIGH</td>
 *       <td>respawn location inside a mirage → schedule a transfer (cause {@code RESPAWN})
 *           on the player's owning thread right after the respawn lands</td></tr>
 *   <tr><td>{@link WorldUnloadEvent}</td><td>HIGH</td>
 *       <td>cancel — mirages must never unload</td></tr>
 *   <tr><td>{@link BlockPlaceEvent} / {@link BlockBreakEvent} / {@link EntityChangeBlockEvent}</td>
 *       <td>HIGH, ignoreCancelled=true</td>
 *       <td>cancel + rate-limited warning per player ({@code mirage.block-edits=cancel});
 *           protects the stub from being filled with blocks</td></tr>
 * </table>
 *
 * <p><b>Idempotency.</b> The player guard never fires a second teleport locally — the transfer
 * completes proxy-side. Events arriving on the target backend ({@code PlayerJoinEvent},
 * {@code PlayerRespawnEvent}) cannot re-enter this guard: they do not carry a teleport to a
 * mirage world with the mirage names of <em>this</em> backend.
 *
 * <p><b>When a transfer is refused</b> (backend degraded, proxy down), the teleport stays
 * cancelled and the player is told the destination is temporarily unreachable — they simply
 * do not move. That is the honest behavior of an unavailable network, identical to any
 * multi-server setup where the target is down.
 *
 * <p><b>Respawn caveat.</b> {@link PlayerRespawnEvent} cannot be cancelled. If the configured
 * respawn point resolves to a mirage, the player briefly spawns in the void at (0, 100, 0)
 * and is rerouted within about a tick. A listener that vetoes
 * {@code DimensionTransferStartEvent} will defeat this rescue (its veto is respected) — the
 * player then dies to the void and respawns again; fix the vetoing plugin.
 */
public final class MirageGuard {

    private final JavaPlugin plugin;
    private final MirageService mirage;
    private final PaperServices services;
    private final CrossServerTransfer executor;
    private final Logger logger;

    private MirageGuard(@NotNull JavaPlugin plugin, @NotNull MirageService mirage,
                        @NotNull PaperServices services, @NotNull CrossServerTransfer executor,
                        @NotNull Logger logger) {
        this.plugin = plugin;
        this.mirage = mirage;
        this.services = services;
        this.executor = executor;
        this.logger = logger;
    }

    /**
     * Registers the guard listeners when mirages are enabled.
     *
     * @return the guard (registered or inert)
     */
    public static @NotNull MirageGuard register(@NotNull JavaPlugin plugin, @NotNull MirageService mirage,
                                                @NotNull PaperServices services,
                                                @NotNull CrossServerTransfer executor) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(mirage, "mirage");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(executor, "executor");
        Logger logger = plugin.getSLF4JLogger();
        MirageGuard guard = new MirageGuard(plugin, mirage, services, executor, logger);
        if (!mirage.enabled()) {
            logger.info("mirage guards: not registered (mirage.enabled=false)");
            return guard;
        }
        plugin.getServer().getPluginManager().registerEvents(guard.new Listeners(), plugin);
        logger.info("mirage guards: registered (teleport/entity-teleport/respawn/world-unload/block-edit)");
        return guard;
    }

    // ------------------------------------------------------------- listeners

    private final class Listeners implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
        public void onPlayerTeleport(PlayerTeleportEvent event) {
            // portal-completion events the portal flows already took over are ours to leave
            // alone — the owning flow has either started the transfer or deliberately refused;
            // either way the crossing must not be requested twice. Plain teleports (e.g. /tp
            // into a mirage) are never skipped by this marker.
            if (event instanceof org.bukkit.event.player.PlayerPortalEvent
                    && NetherCrossingMarker.isMarked(event.getPlayer().getUniqueId())) {
                return;
            }
            Location to = event.getTo();
            World targetWorld = to == null ? null : to.getWorld();
            DimensionId targetDimension = mirage.dimensionOf(targetWorld).orElse(null);
            if (targetDimension == null) {
                return; // not a mirage — leave the teleport alone
            }
            event.setCancelled(true);
            mirage.metrics().recordTeleportCancelled();
            Player player = event.getPlayer();
            TransferCommand command = new TransferCommand(player, targetDimension,
                    clampedArrival(targetDimension, to), TransferCause.MIRAGE_TELEPORT);
            logger.debug("mirage: intercepting teleport of {} into mirage '{}' → {} (cause={})",
                    player.getUniqueId(), targetWorld.getName(), targetDimension, command.cause());
            try {
                var result = executor.transfer(command);
                if (!result.ok()) {
                    logger.warn("mirage: transfer of {} refused: {} ({})",
                            player.getUniqueId(), result.error(), result.message());
                    player.sendMessage(Component.text(
                            "That dimension is on another server, and the transfer could not start right now. "
                                    + "Please try again in a moment."));
                }
            } catch (RuntimeException e) {
                logger.error("SEVERE: mirage transfer of {} failed to start: {}",
                        player.getUniqueId(), e.toString());
                player.sendMessage(Component.text(
                        "That dimension is on another server, and the transfer could not start right now. "
                                + "Please try again in a moment."));
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
        public void onEntityTeleport(EntityTeleportEvent event) {
            Location to = event.getTo();
            World targetWorld = to == null ? null : to.getWorld();
            if (!mirage.isMirage(targetWorld)) {
                return;
            }
            event.setCancelled(true);
            mirage.metrics().recordEntityTeleportCancelled();
            // entity transfers do not exist yet; the entity simply stays where it is.
            logger.debug("mirage: cancelled entity teleport of {} ({}) into mirage '{}' — entity transfers land in a later phase",
                    event.getEntity().getUniqueId(), event.getEntity().getType(), targetWorld.getName());
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onRespawn(PlayerRespawnEvent event) {
            Location respawn = event.getRespawnLocation();
            World world = respawn == null ? null : respawn.getWorld();
            DimensionId targetDimension = mirage.dimensionOf(world).orElse(null);
            if (targetDimension == null) {
                return;
            }
            Player player = event.getPlayer();
            mirage.metrics().recordRespawnRedirect();
            Schedulers schedulers = services.schedulers();
            // runOn() after the respawn lands — the entity owns its thread again by then
            schedulers.runOn(player, () -> {
                try {
                    TransferCommand command = new TransferCommand(player, targetDimension,
                            clampedArrival(targetDimension, respawn), TransferCause.RESPAWN);
                    var result = executor.transfer(command);
                    if (!result.ok()) {
                        logger.error("SEVERE: mirage respawn reroute of {} refused: {} ({})",
                                player.getUniqueId(), result.error(), result.message());
                        player.sendMessage(Component.text(
                                "You respawned in a stub world. The connection to the real dimension could not be "
                                        + "started; please reconnect, or contact an administrator."));
                    }
                } catch (RuntimeException e) {
                    logger.error("SEVERE: mirage respawn reroute of {} failed: {}",
                            player.getUniqueId(), e.toString());
                    player.sendMessage(Component.text(
                            "You respawned in a stub world. The connection to the real dimension could not be "
                                    + "started; please reconnect, or contact an administrator."));
                }
            });
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onWorldUnload(WorldUnloadEvent event) {
            if (!mirage.isMirage(event.getWorld())) {
                return;
            }
            event.setCancelled(true);
            mirage.metrics().recordUnloadCancelled();
            logger.debug("mirage: cancelled unload of mirage '{}'", event.getWorld().getName());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onBlockPlace(BlockPlaceEvent event) {
            cancelMirageEdit(event.getPlayer(), event.getBlock(), event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event) {
            cancelMirageEdit(event.getPlayer(), event.getBlock(), event);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEntityChangeBlock(EntityChangeBlockEvent event) {
            Entity entity = event.getEntity();
            if (entity instanceof Player player) {
                cancelMirageEdit(player, event.getBlock(), event);
                return;
            }
            if (mirage.isMirage(event.getBlock().getWorld())) {
                event.setCancelled(true);
                mirage.metrics().recordBlockEditCancelled();
                logger.debug("mirage: cancelled block change by {} ({}) in mirage '{}'",
                        entity.getUniqueId(), entity.getType(), event.getBlock().getWorld().getName());
            }
        }
    }

    // ------------------------------------------------------------- shared bits

    private void cancelMirageEdit(Player player, Block block, org.bukkit.event.Cancellable event) {
        if (!mirage.isMirage(block.getWorld())) {
            return;
        }
        event.setCancelled(true);
        mirage.metrics().recordBlockEditCancelled();
        logger.warn("mirage: cancelled block edit by {} in mirage '{}' ({})",
                player.getUniqueId(), block.getWorld().getName(), block.getType());
        // mirage.block-edits has exactly one supported value today ("cancel"); the warning is
        // rate-limited so a grief loop doesn't spam the chat or the log
        if (mirage.metrics().tryAcquireBlockEditWarning(player.getUniqueId(), System.currentTimeMillis())) {
            player.sendMessage(Component.text("You cannot build in this world."));
        }
    }

    private static @NotNull Position clampedArrival(@NotNull DimensionId targetDimension, @NotNull Location to) {
        return ArrivalClamp.arrival(targetDimension,
                new Position(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch()));
    }

    /** For tests and admin tooling: is this world one of ours? */
    public boolean isMirage(@NotNull World world) {
        return mirage.isMirage(world);
    }
}

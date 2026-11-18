package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.nether.NetherCoordinateConverter;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.paper.transfer.PortalTransferService;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Nether portal completion handling: the portal transition is always cancelled (vanilla
 * cannot cross servers) and routed into the cross-server transfer pipeline.
 *
 * <p><b>Timing.</b> On Paper the vanilla delay has already elapsed by the time this event
 * fires — {@code EntityPortalReadyEvent} fires after the required (gamerule) ticks and
 * {@code PlayerPortalEvent} follows it at the teleport-completion point. The gamerule delay,
 * the early-exit reset and the creative bypass are therefore all honoured by vanilla itself;
 * this listener replaces only the teleport with the cross-server switch. No parallel dwell
 * timer exists, by design — see the mechanics guide for the full analysis.
 *
 * <p><b>Coexistence.</b> Runs at NORMAL with {@code ignoreCancelled=true}: protection-style
 * plugins that veto first (LOWEST/LOW) are respected — the portal stays inert. The mirage
 * teleport guard runs later (HIGH) and skips events marked by
 * {@link NetherCrossingMarker}, so a handled crossing never produces a second transfer.
 * Any cancellation is honoured — but the marker is only set when this listener takes
 * ownership, never for vetoed portals.
 *
 * <p>Known approximation vs vanilla: the pre-switch clamp of the ideal destination uses the
 * default bounds ({@link WorldBorderBounds#DEFAULT_NETHER} /
 * {@link WorldBorderBounds#DEFAULT_OVERWORLD}) because the destination backend's configured
 * border cannot be read before the switch. The arrival search on the destination backend
 * applies its real border; only a custom border set exclusively on the remote backend is
 * approximated.
 */
public final class NetherPortalListener implements Listener {

    /** Window for the rate-limited refusal message (the transfer itself is never rate-limited). */
    private static final long REFUSAL_MESSAGE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int REFUSAL_MESSAGE_CAP = 1024;

    private final TransferBackend backend;
    private final PortalTransferService transfers;
    private final ConcurrentHashMap<java.util.UUID, Long> lastRefusalMessage = new ConcurrentHashMap<>();

    public NetherPortalListener(TransferBackend backend, PortalTransferService transfers) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.transfers = java.util.Objects.requireNonNull(transfers, "transfers");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        World world = event.getFrom().getWorld();
        if (world == null) {
            return;
        }
        World.Environment env = world.getEnvironment();
        DimensionId from;
        DimensionId to;
        switch (env) {
            case NORMAL -> {
                from = DimensionId.OVERWORLD;
                to = DimensionId.NETHER;
            }
            case NETHER -> {
                from = DimensionId.NETHER;
                to = DimensionId.OVERWORLD;
            }
            default -> {
                // end portals and custom environments are not ours to route
                return;
            }
        }

        // The vanilla transition can never stand on a split-world backend.
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!backend.config().portals().nether().enabled()) {
            refuse(player, "Nether portals are disabled on this network.");
            return;
        }
        if (!NetherCrossingMarker.tryMark(player.getUniqueId())) {
            // a duplicate completion for the same dwell — vanilla stays cancelled, one crossing only
            backend.diagnostics().accept("nether portal: suppressed re-entry for player " + player.getUniqueId());
            return;
        }

        Position ideal = idealDestination(event.getFrom(), env);
        NetherPortalGamerules gamerules = new BukkitNetherPortalGamerules(world);
        backend.diagnostics().accept("nether portal completion for player " + player.getUniqueId()
                + " from " + from + " to " + to
                + " (gamerules: " + NetherPortalGamerules.DEFAULT_DELAY_KEY + "="
                + gamerules.playersNetherPortalDefaultDelay()
                + ", " + NetherPortalGamerules.CREATIVE_DELAY_KEY + "="
                + gamerules.playersNetherPortalCreativeDelay() + ")");

        PortalTransferService.Outcome outcome = transfers.transfer(
                player, from, to, TransferCause.NETHER_PORTAL, ideal);
        if (outcome instanceof PortalTransferService.Outcome.Refused refused) {
            refuse(player, refused.reason());
        }
        // Started: the proxy takes the player; Cancelled: an integration listener vetoed — both are fine
    }

    private Position idealDestination(Location from, World.Environment env) {
        Position source = new Position(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch());
        if (env == World.Environment.NORMAL) {
            return NetherCoordinateConverter.overworldToNether(source, WorldBorderBounds.DEFAULT_NETHER);
        }
        return NetherCoordinateConverter.netherToOverworld(source, WorldBorderBounds.DEFAULT_OVERWORLD);
    }

    private void refuse(Player player, String reason) {
        backend.diagnostics().accept("nether portal transfer refused for player "
                + player.getUniqueId() + ": " + reason);
        long now = System.nanoTime();
        if (lastRefusalMessage.size() >= REFUSAL_MESSAGE_CAP) {
            lastRefusalMessage.clear();
        }
        Long last = lastRefusalMessage.get(player.getUniqueId());
        if (last != null && now - last < REFUSAL_MESSAGE_WINDOW_NANOS) {
            return;   // message already delivered seconds ago — don't spam the chat
        }
        lastRefusalMessage.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("You cannot travel there right now. "
                + "Please try again in a moment, or tell an administrator."));
    }
}

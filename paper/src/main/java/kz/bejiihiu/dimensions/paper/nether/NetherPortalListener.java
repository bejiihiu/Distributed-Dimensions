package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.nether.NetherCoordinateConverter;
import kz.bejiihiu.dimensions.common.nether.PortalExitCalculator;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.TransferSourceContext;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.compat.CooldownStore;
import kz.bejiihiu.dimensions.compat.PlayerPortalCooldowns;
import kz.bejiihiu.dimensions.entities.EntityPortalScanner;
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
import org.jetbrains.annotations.Nullable;

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
 * <p><b>Portal cooldown.</b> Arrival stamps the player's PDC with the 300 t portal cooldown
 * (arrival side, whenever the nether arrival step runs); this listener consults the same
 * stamp before routing a completion and suppresses re-entry mid-cooldown instead of
 * transferring (players riding a vehicle are exempt — vanilla assembly semantics).
 *
 * <p><b>Source context.</b> Before routing, the completion also captures the source portal's
 * inner rectangle (a POI scan around the event location, the same one the entity namespace
 * uses) and the player's hitbox-reduced relative spot inside it. The context rides the
 * shared database with the operation id; the arrival side re-applies it on the destination
 * portal, preserving the player's relative spot exactly like vanilla's exit-placement math.
 * If the context cannot be captured (POI unavailable, no portal blocks readable) the transfer
 * still proceeds — the arrival then places the player at the inner rectangle's bottom-middle.
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
    private final CooldownStore cooldowns;
    private final ConcurrentHashMap<java.util.UUID, Long> lastRefusalMessage = new ConcurrentHashMap<>();

    public NetherPortalListener(TransferBackend backend, PortalTransferService transfers,
                                CooldownStore cooldowns) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.transfers = java.util.Objects.requireNonNull(transfers, "transfers");
        this.cooldowns = java.util.Objects.requireNonNull(cooldowns, "cooldowns");
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

        // vanilla suppresses re-entry while the traveller sits inside the portal they just
        // exited (the 300 t portal cooldown). We honour the same window via PDC, so a player
        // whose arrival lands them back inside the exit portal is not ping-ponged: the
        // completion is cancelled silently — vanilla stays vetoed, no transfer happens.
        // The crossing is still marked: an unmarked cancelled completion would be picked up
        // by the mirage guard and request a second transfer — exactly the ping-pong this
        // cooldown exists to break.
        Player enteredPlayer = event.getPlayer();
        if (PlayerPortalCooldowns.isCoolingDown(cooldowns, enteredPlayer, System.currentTimeMillis())) {
            event.setCancelled(true);
            NetherCrossingMarker.tryMark(enteredPlayer.getUniqueId());
            backend.diagnostics().accept("nether portal completion suppressed by portal cooldown for player "
                    + enteredPlayer.getUniqueId());
            return;
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
        TransferSourceContext sourceContext = buildSourceContext(world, event.getFrom(), player);
        NetherPortalGamerules gamerules = new BukkitNetherPortalGamerules(world);
        backend.diagnostics().accept("nether portal completion for player " + player.getUniqueId()
                + " from " + from + " to " + to
                + " (gamerules: " + NetherPortalGamerules.DEFAULT_DELAY_KEY + "="
                + gamerules.playersNetherPortalDefaultDelay()
                + ", " + NetherPortalGamerules.CREATIVE_DELAY_KEY + "="
                + gamerules.playersNetherPortalCreativeDelay() + ")");

        PortalTransferService.Outcome outcome = transfers.transfer(
                player, from, to, TransferCause.NETHER_PORTAL, ideal, sourceContext);
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

    /**
     * The source portal's relative-position context, computed from the blocks at the event
     * location. Reuses the POI scan the entity transfer uses (chunk-section lookup, never a
     * world scan); any failure — POI unavailable, an unreadable hitbox, a fake world in a
     * unit test — yields null and the target falls back to bottom-middle placement. The
     * transfer proceeds regardless; the context is an optimization on top of that baseline.
     */
    private @Nullable TransferSourceContext buildSourceContext(World world, Location from, Player player) {
        try {
            double width = player.getWidth();
            double height = player.getHeight();
            BlockPos eventBlock = new BlockPos(from.getBlockX(), from.getBlockY(), from.getBlockZ());
            java.util.Optional<PortalInnerRect> rect = EntityPortalScanner.sourceRect(world, from, eventBlock);
            if (rect.isPresent()) {
                return sourceContextOf(rect.get(),
                        new Position(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch()),
                        width, height);
            }
            backend.diagnostics().accept("nether portal: source portal rect not resolvable at the event location; "
                    + "the arrival falls back to bottom-middle placement");
            return null;
        } catch (Throwable t) {
            backend.diagnostics().accept("nether portal: source context unavailable (" + t
                    + "); the arrival falls back to bottom-middle placement");
            return null;
        }
    }

    /**
     * The hitbox-reduced relative spot of a position inside the source rectangle — the
     * world-independent half, exercised directly by unit tests. Living-entity rules apply:
     * the forward offset is dropped.
     */
    static TransferSourceContext sourceContextOf(PortalInnerRect rect, Position from,
                                                 double width, double height) {
        RelativePortalPosition relative = PortalExitCalculator.relativePosition(
                rect, rect.axis(), from, width, height, true);
        return new TransferSourceContext(rect.axis(), relative, width, height);
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

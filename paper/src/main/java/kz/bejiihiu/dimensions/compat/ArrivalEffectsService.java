package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.TransferCause;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.interop.InteropMapping;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Arrival-side compatibility effects, applied on the backend the player lands on:
 *
 * <ol>
 *   <li><b>Compat A — advancement grants.</b> When the attributed operation's cause is
 *       {@code NETHER_PORTAL} or {@code END_PORTAL}, the matching criterion is awarded
 *       idempotently (see {@link AdvancementGranter}).</li>
 *   <li><b>Compat B — synthetic portal events</b> (off unless
 *       {@code api.compat.fire-synthetic-events=true}). A {@code PlayerPortalEvent} and a
 *       {@code PlayerTeleportEvent} are dispatched with {@code from == to ==} the arrival
 *       point and a {@code TeleportCause} of {@code NETHER_PORTAL}/{@code END_PORTAL}.
 *       Listeners that watch the cause see a portal teleport; listeners reading {@code from}
 *       see a zero-distance hop — this is the documented, unavoidable shape of a synthetic
 *       event and the reason B is opt-in and off by default.</li>
 * </ol>
 *
 * <p><b>Attribution.</b> The backend cannot ask the proxy which operation just landed, so the
 * effect is attributed by scanning the shared operation journal for a fresh row matching this
 * player and the joined world's canonical dimension ({@link ArrivalAttribution}). The scan runs
 * async; effects dispatch on the player's owning thread. Failures there are silent to callers —
 * the grants are idempotent, no event depends on them, and a missing grant is a cosmetic loss.
 *
 * <p><b>Synthetic-event dedup.</b> An operation can in principle be attributed on several joins
 * within the freshness window (join → quit → re-join). The events fire at most once per
 * operation id per JVM (5-minute TTL); advancement grants need no such dedup (idempotent).
 *
 * <p><b>Never dispatched:</b> {@code PlayerChangedWorldEvent} (there is no honest source world
 * and its constructor is {@code @Internal}), {@code EntityPortalEvent} (never synthesized),
 * {@code EntityTransferEvent} (Phase 5). A synthetic {@code PlayerTeleportEvent} whose cause is
 * not a portal flavor is never fired either — mirage/programmatic/respawn arrivals dispatch
 * nothing.
 *
 * <p><b>Honest caveat (Paper 26.3).</b> Every constructor of {@code PlayerPortalEvent} /
 * {@code PlayerTeleportEvent} is annotated {@code @ApiStatus.Internal} on this version (it was
 * already internal in the published javadoc for 26.3). The calls compile and dispatch normally —
 * the events are inert after the fact (no portal logic runs) — but Paper reserves the right to
 * change those constructors without notice. They are called exclusively behind a default-off flag,
 * and nothing else in this plugin depends on them. See {@code docs/compat.md}.
 *
 * <p><b>Ordering.</b> This runs on {@code PlayerJoinEvent}. Paper fires our own
 * {@code DimensionTransferCompleteEvent} only after the sync plugin reports the join settled, so a
 * listener that wants "after everything settles" should trust CompleteEvent, not this class.
 * Advancement grants and synthetic events here are early by design.
 */
public final class ArrivalEffectsService {

    /** How long an operation id suppresses re-firing synthetic events. */
    private static final long SYNTHETIC_DEDUP_MILLIS = 300_000L;
    /** How many journal rows to scan for attribution per join. */
    private static final int JOURNAL_SCAN_LIMIT = 50;

    private final JavaPlugin plugin;
    private final PaperServices services;
    private final Logger logger;
    private final boolean grantAdvancements;
    private final boolean fireSyntheticEvents;
    private final AdvancementGranter granter;
    private final ConcurrentHashMap<UUID, Long> syntheticFiredAt = new ConcurrentHashMap<>();
    private final LongAdder advancementsGranted = new LongAdder();
    private final LongAdder syntheticEventsFired = new LongAdder();

    private ArrivalEffectsService(@NotNull JavaPlugin plugin, @NotNull PaperServices services,
                                  @NotNull Logger logger, boolean grantAdvancements, boolean fireSyntheticEvents) {
        this.plugin = plugin;
        this.services = services;
        this.logger = logger;
        this.grantAdvancements = grantAdvancements;
        this.fireSyntheticEvents = fireSyntheticEvents;
        this.granter = new AdvancementGranter(logger);
    }

    /**
     * Registers the join listener when at least one arrival effect is enabled.
     *
     * @return the service (inert when disabled — no listener registered)
     */
    public static @NotNull ArrivalEffectsService registerIfEnabled(@NotNull JavaPlugin plugin,
                                                                   @NotNull PaperServices services) {
        Logger logger = plugin.getSLF4JLogger();
        boolean enabled = services.config().api().enabled();
        boolean grant = enabled && services.config().api().compat().grantAdvancements();
        boolean synth = enabled && services.config().api().compat().fireSyntheticEvents();
        ArrivalEffectsService service = new ArrivalEffectsService(plugin, services, logger, grant, synth);
        if (!grant && !synth) {
            logger.info("arrival effects: disabled by configuration (api.enabled={}, api.compat.grant-advancements={}, api.compat.fire-synthetic-events={})",
                    enabled, services.config().api().compat().grantAdvancements(),
                    services.config().api().compat().fireSyntheticEvents());
            return service;
        }
        plugin.getServer().getPluginManager().registerEvents(service.new Listeners(), plugin);
        logger.info("arrival effects: enabled (advancements={}, synthetic-events={})", grant, synth);
        return service;
    }

    // ------------------------------------------------------------- listener

    private final class Listeners implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            World.Environment env = player.getWorld().getEnvironment();
            Optional<DimensionId> joined = ArrivalAttribution.dimensionFor(env);
            if (joined.isEmpty()) {
                logger.debug("arrival attribution: custom environment for join of {} — nothing to attribute",
                        player.getUniqueId());
                return;
            }
            Schedulers schedulers = services.schedulers();
            schedulers.runAsync(() -> {
                final List<TransferOperation> recent;
                try {
                    recent = services.journal().listRecent(JOURNAL_SCAN_LIMIT);
                } catch (RuntimeException e) {
                    logger.debug("arrival attribution: journal unavailable ({}); skipping effects for {}",
                            e.toString(), player.getUniqueId());
                    return;
                }
                Optional<TransferOperation> row = ArrivalAttribution.select(
                        recent, player.getUniqueId(), joined.get(), Instant.now());
                row.ifPresentOrElse(
                        op -> schedulers.runOn(player, () -> apply(player, op)),
                        () -> logger.debug("arrival attribution: no fresh journal row for {} in {} — no effects",
                                player.getUniqueId(), joined.get()));
            });
        }
    }

    // ------------------------------------------------------------- effects

    private void apply(@NotNull Player player, @NotNull TransferOperation operation) {
        TransferCause cause = InteropMapping.apiCause(operation.cause());
        logger.debug("arrival attribution for {}: {}", player.getUniqueId(), ArrivalAttribution.describe(operation));

        if (grantAdvancements) {
            AdvancementGranter.Outcome outcome = granter.grant(player, cause);
            if (outcome == AdvancementGranter.Outcome.GRANTED) {
                advancementsGranted.increment();
                AdvancementGranter.AdvancementRule rule = AdvancementGranter.ruleFor(cause);
                logger.info("compat A: awarded {} criterion to {} (operation {})",
                        rule == null ? "?" : rule.criterion(), player.getUniqueId(), operation.operationId());
            } else {
                logger.debug("compat A: advancement outcome {} for {} (operation {})",
                        outcome, player.getUniqueId(), operation.operationId());
            }
        }

        if (fireSyntheticEvents && (cause == TransferCause.NETHER_PORTAL || cause == TransferCause.END_PORTAL)) {
            fireSynthetic(player, operation.operationId(), cause);
        }
    }

    private void fireSynthetic(@NotNull Player player, @NotNull UUID operationId, @NotNull TransferCause cause) {
        pruneSyntheticDedup(System.currentTimeMillis());
        Long previously = syntheticFiredAt.putIfAbsent(operationId, System.currentTimeMillis());
        if (previously != null) {
            logger.debug("compat B: synthetic events for operation {} already fired — skipping re-dispatch", operationId);
            return;
        }
        PlayerTeleportEvent.TeleportCause bukkitCause = cause == TransferCause.NETHER_PORTAL
                ? PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                : PlayerTeleportEvent.TeleportCause.END_PORTAL;
        Location here = player.getLocation().clone();

        // best-effort post-arrival simulation; cancellations by listeners are accepted as advisory no-ops
        boolean portalCancelled = callEventQuietly(new PlayerPortalEvent(player, here.clone(), here.clone(), bukkitCause));
        boolean teleportCancelled = callEventQuietly(new PlayerTeleportEvent(player, here.clone(), here.clone(), bukkitCause));
        syntheticEventsFired.add(2);
        logger.info("compat B: synthetic events dispatched for operation {} (cause={}, portalEventCancelled={}, teleportEventCancelled={})",
                operationId, bukkitCause, portalCancelled, teleportCancelled);
    }

    private boolean callEventQuietly(@NotNull org.bukkit.event.Event event) {
        try {
            plugin.getServer().getPluginManager().callEvent(event);
            if (event instanceof org.bukkit.event.Cancellable cancellable) {
                return cancellable.isCancelled();
            }
            return false;
        } catch (RuntimeException e) {
            // a misbehaving listener must not break the join pipeline
            logger.warn("compat B: a listener threw on the synthetic {} — swallowed: {}",
                    event.getEventName(), e.toString());
            return false;
        }
    }

    private void pruneSyntheticDedup(long nowMillis) {
        syntheticFiredAt.entrySet().removeIf(entry -> nowMillis - entry.getValue() > SYNTHETIC_DEDUP_MILLIS);
    }

    /** Diagnostics counters. */
    public long advancementsGranted() {
        return advancementsGranted.sum();
    }

    public long syntheticEventsFired() {
        return syntheticEventsFired.sum();
    }
}

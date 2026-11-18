package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.common.config.SyncSettings;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Builds the {@link SyncIntegration} for this boot, applying the selection decision
 * and the loud-failure fallbacks, and prints the two discovery lines every
 * server operator sees at startup (never more than one of each per boot).
 */
public final class SyncIntegrationFactory {

    private SyncIntegrationFactory() {
    }

    /**
     * Select and bind the integration. Never throws; every failure path lands on
     * a loud, working integration (see {@link StrategySelection}).
     */
    public static SyncIntegration bind(Plugin plugin, Schedulers schedulers,
            SyncSettings settings, Predicate<UUID> onlineCheck, LongSupplier clock, Logger logger) {
        String mode = settings.plugin().trim().toLowerCase(java.util.Locale.ROOT);
        long waitTimeoutMillis = settings.waitTimeout().toMillis();

        boolean huskSyncApi = HuskSyncCompileIntegration.probe(plugin);
        String knownSyncPlugin = StrategySelection.findKnownSyncPlugin(installedPluginNames(plugin));
        boolean reflectionConfigured = settings.reflectionConfigured();

        StrategySelection.Decision decision = StrategySelection.select(
                mode, huskSyncApi, reflectionConfigured, knownSyncPlugin);

        SyncIntegration integration = switch (decision.strategy()) {
            case HUSKSYNC_API -> unwrap(HuskSyncCompileIntegration.tryBind(
                    plugin, schedulers, waitTimeoutMillis, onlineCheck, clock, logger));
            case REFLECTION -> unwrap(ReflectionSyncIntegration.tryBind(
                    plugin, schedulers, settings, onlineCheck, clock, logger));
            case PRESENCE_DELAY -> new PresenceDelayedIntegration(
                    schedulers, onlineCheck, settings.waitTicks(), waitTimeoutMillis, clock,
                    knownSyncPlugin, logger);
            case DEGRADED_FALLBACK -> new DegradedFallbackIntegration(
                    schedulers, onlineCheck, settings.waitTicks(), waitTimeoutMillis, clock,
                    decision.note(), logger);
            case LOUD_FAILURE -> new SyncIntegration.LoggingFallback(
                    decision.note(), schedulers, waitTimeoutMillis, logger);
        };

        // An auto-selected binding that failed mid-flight degrades stepwise instead of giving up on the
        // first option; an explicitly pinned binding fails loudly (the admin asked for exactly this one).
        if (integration == null) {
            integration = fallBackAfterFailedBinding(mode, settings, schedulers, onlineCheck, clock,
                    logger, plugin, knownSyncPlugin);
        }

        String pluginLabel = pluginLabel(integration, knownSyncPlugin, settings);
        logger.info("sync: level {} bound — strategy={}, plugin={}, mode={}, waitTimeout={} ms, waitTicks={}",
                levelOf(integration), integration.id(), pluginLabel, mode, waitTimeoutMillis, settings.waitTicks());
        logger.info("Distributed Dimensions: waiting for player sync via {} ({})",
                integration.id(), pluginLabel);
        return integration;
    }

    private static SyncIntegration unwrap(java.util.Optional<? extends SyncIntegration> bound) {
        return bound.isPresent() ? bound.get() : null;
    }

    /**
     * Auto-mode downgrade chain after a failed binding (the pre-check raced the
     * actual bind, or the jar is broken): reflection if configured, then a known
     * presence plugin, then the degraded floor. Never throws.
     */
    private static SyncIntegration fallBackAfterFailedBinding(String mode, SyncSettings settings,
            Schedulers schedulers, Predicate<UUID> onlineCheck, LongSupplier clock, Logger logger,
            Plugin plugin, String knownSyncPlugin) {
        long waitTimeoutMillis = settings.waitTimeout().toMillis();
        if (!"auto".equals(mode)) {
            logger.error("SEVERE: configured sync integration could not be armed (sync.plugin={}). "
                            + "Install HuskSync or fix sync.plugin in config.yml; transfers will FAIL "
                            + "after the sync wait timeout until then.", mode);
            return new SyncIntegration.LoggingFallback(
                    "configured integration (sync.plugin=" + mode + ") could not be armed",
                    schedulers, waitTimeoutMillis, logger);
        }
        logger.warn("sync: auto binding failed — trying the next fallback tier");
        if (settings.reflectionConfigured()) {
            java.util.Optional<ReflectionSyncIntegration> b = ReflectionSyncIntegration.tryBind(
                    plugin, schedulers, settings, onlineCheck, clock, logger);
            if (b.isPresent()) {
                return b.get();
            }
        }
        if (knownSyncPlugin != null) {
            return new PresenceDelayedIntegration(schedulers, onlineCheck, settings.waitTicks(),
                    waitTimeoutMillis, clock, knownSyncPlugin, logger);
        }
        return new DegradedFallbackIntegration(schedulers, onlineCheck, settings.waitTicks(),
                waitTimeoutMillis, clock, "auto binding failed (all tiers incompatible)", logger);
    }

    private static String levelOf(SyncIntegration integration) {
        if (integration instanceof HuskSyncCompileIntegration) {
            return "A (husksync-api)";
        }
        if (integration instanceof ReflectionSyncIntegration) {
            return "B (reflection)";
        }
        if (integration instanceof PresenceDelayedIntegration) {
            return "C (presence-delay)";
        }
        if (integration instanceof DegradedFallbackIntegration) {
            return "D (degraded-fallback)";
        }
        return "loud-failure (fallback armed)";
    }

    private static String pluginLabel(SyncIntegration integration, String knownSyncPlugin,
            SyncSettings settings) {
        if (integration instanceof HuskSyncCompileIntegration) {
            return "HuskSync";
        }
        if (integration instanceof ReflectionSyncIntegration) {
            return settings.reflectionEventClass();
        }
        return knownSyncPlugin != null ? knownSyncPlugin : "none";
    }

    private static List<String> installedPluginNames(Plugin plugin) {
        PluginManager manager = plugin.getServer().getPluginManager();
        List<String> names = new ArrayList<>();
        for (Plugin installed : manager.getPlugins()) {
            try {
                names.add(installed.getName());
            } catch (Throwable t) {
                // a broken plugin instance carries no name — skip it, never fail boot
            }
        }
        return names;
    }
}

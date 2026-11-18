package kz.bejiihiu.dimensions.admin;

import kz.bejiihiu.dimensions.common.config.*;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.mirage.MirageService;
import kz.bejiihiu.dimensions.mirage.MirageWorld;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.HealthReport;
import kz.bejiihiu.dimensions.paper.sync.SyncServices;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The admin command surface of the backend: {@code /dd}.
 *
 * <p><b>Boot wiring.</b> {@code DistributedDimensionsPaperPlugin#onEnable} calls
 * {@link #install} once, after the interop stage (stage 8 of the boot pipeline).
 * {@code install} loads the message bundles (fail-fast at this call site: a broken
 * or mismatched bundle logs SEVERE and the command is simply not registered — the
 * backend keeps working), selects the language from the running configuration and
 * registers {@code /dd} through {@link JavaPlugin#registerCommand}, which ties the
 * command's lifecycle to the plugin (no manual unregistration on disable).
 *
 * <p><b>What the command can and cannot do.</b> The backend's services are
 * constructor-wired at boot, so a real hot-swap of configuration does not exist.
 * {@code /dd reload} therefore re-reads and re-validates {@code config.yml} (migrations
 * run, broken files are caught, unknown keys are reported) and re-applies exactly one
 * key — {@code general.language} — re-selecting the message bundle live. Every other
 * section is reported as changed-but-restart-required by {@link ConfigDelta} and is
 * deliberately never swapped. See docs/commands.md for the exact semantics.
 */
public final class AdminSurface {

    private static final String COMMAND_LABEL = "dd";
    private static final String COMMAND_DESCRIPTION = "Distributed Dimensions backend admin (info, reload, mirage)";

    private AdminSurface() {
    }

    /**
     * Loads the bundles, selects the language and registers {@code /dd}.
     *
     * @param syncServices may be {@code null} when the sync bootstrap failed catastrophically;
     *                     {@code /dd info} then reports sync as not bound instead of throwing
     */
    public static void install(JavaPlugin plugin, PaperServices services,
                               ConfigurationService configService, MirageService mirageService,
                               SyncServices syncServices) {
        Logger logger = plugin.getSLF4JLogger();

        Messages messages;
        try {
            // Messages is instantiated from the plugin classloader via our own class:
            // the bundles are flat classpath resources next to this class.
            messages = new Messages(Messages.class.getClassLoader(), logger::warn);
        } catch (RuntimeException e) {
            // A broken bundle is a build defect; the backend must stay usable without /dd.
            logger.error("SEVERE: admin message bundles failed to load ({}); /dd will not be available until this is fixed",
                    e.getMessage());
            return;
        }
        messages.select(services.config().general().language());

        ProductionServices backend = new ProductionServices(plugin, services, configService,
                mirageService, syncServices, messages, System.currentTimeMillis());
        plugin.registerCommand(COMMAND_LABEL, COMMAND_DESCRIPTION, new DdCommand(backend, messages));
        logger.info("admin surface: /{} registered ({}), permission '{}'", COMMAND_LABEL,
                String.join(", ", DdCommand.SUBCOMMANDS), DdCommand.PERMISSION);
    }

    // ------------------------------------------------------------------ production backend

    private record ProductionServices(JavaPlugin plugin, PaperServices services, ConfigurationService configService,
                                      MirageService mirageService, SyncServices syncServices, Messages messages,
                                      long enabledAtMillis) implements DdCommand.Services {

        @Override
            public AdminInfo info() {
                try {
                    PluginConfig running = services.config();
                    HealthReport health = services.health();
                    List<AdminInfo.DimensionRoute> routes = new ArrayList<>();
                    for (Map.Entry<DimensionId, DimensionTarget> entry
                        : services.dimensions().routes().entrySet()) {
                        routes.add(new AdminInfo.DimensionRoute(entry.getKey().toString(), entry.getValue().server()));
                    }
                    LoadReport report = configService.report();
                    return new AdminInfo(
                        plugin.getPluginMeta().getVersion(),
                        Bukkit.getVersion(),
                        health.status().name(),
                        health.reasons(),
                        report != null ? report.source().name() : "unknown",
                        report != null && report.fileCreated(),
                        running.configVersion(),
                        report != null && report.migratedFrom() >= 0 && report.migratedTo() >= 0,
                        report == null ? -1 : report.migratedFrom(),
                        report == null ? -1 : report.migratedTo(),
                        running.database().type().id(),
                        running.database().maximumPoolSize(),
                        running.database().minimumIdle(),
                        running.database().tablePrefix(),
                        routes,
                        mirageService.enabled(),
                        mirageService.mirages().size(),
                        mirageService.failures().size(),
                        syncStrategy(),
                        syncPhase(),
                        running.sync().waitForSync(),
                        running.api().enabled(),
                        running.api().compat().fireSyntheticEvents(),
                        running.api().compat().grantAdvancements(),
                        Math.max(0, System.currentTimeMillis() - enabledAtMillis));
                } catch (RuntimeException e) {
                    // An info probe must never take the command down; report it as degraded data.
                    return new AdminInfo(
                        plugin.getPluginMeta().getVersion(),
                        Bukkit.getVersion(),
                        HealthReport.Status.DEGRADED.name(),
                        List.of("info snapshot failed: " + e.getMessage()),
                        "unknown", false, 0, false, -1, -1,
                        "unavailable", 0, 0, "unavailable",
                        List.of(), false, 0, 0,
                        null, "UNAVAILABLE", false,
                        false, false, false,
                        Math.max(0, System.currentTimeMillis() - enabledAtMillis));
                }
            }

            @Override
            public ReloadOutcome reload() {
                PluginConfig running = services.config();
                try {
                    PluginConfig reloaded = configService.load();
                    LoadReport report = configService.report();
                    if (!configService.isHealthy()) {
                        String reason = configService.health()
                            .map(ConfigurationException::getMessage)
                            .orElse("configuration fell back to a non-clean state");
                        return ReloadOutcome.failure(
                            report == null ? -1 : report.configVersion(),
                            report == null ? "unknown" : report.source().name(),
                            reason);
                    }
                    ConfigDelta.Result delta = ConfigDelta.diff(running, reloaded);
                    String newLanguage = null;
                    if (delta.languageChanged()) {
                        messages.select(reloaded.general().language());
                        newLanguage = reloaded.general().language();
                    }
                    return new ReloadOutcome(true,
                        report == null ? reloaded.configVersion() : report.configVersion(),
                        report == null ? "unknown" : report.source().name(),
                        delta.changedSections(),
                        delta.previousLanguage(),
                        newLanguage,
                        report == null ? -1 : report.migratedFrom(),
                        report == null ? -1 : report.migratedTo(),
                        report == null ? List.of() : report.warnings(),
                        null);
                } catch (RuntimeException e) {
                    return ReloadOutcome.failure(running.configVersion(), "unknown",
                        "reload re-scan failed: " + e.getMessage());
                }
            }

            @Override
            public MirageInfo mirages() {
                try {
                    if (!mirageService.enabled()) {
                        return new MirageInfo(false, List.of(), List.of());
                    }
                    List<MirageInfo.Entry> entries = new ArrayList<>();
                    for (MirageWorld world : mirageService.mirages()) {
                        entries.add(new MirageInfo.Entry(world.name(), world.environment().name(),
                            world.dimension().toString(), world.createdByPlugin()));
                    }
                    return new MirageInfo(true, entries, mirageService.failures());
                } catch (RuntimeException e) {
                    return new MirageInfo(true, List.of(), List.of("mirage registry probe failed: " + e.getMessage()));
                }
            }

            private String syncStrategy() {
                if (syncServices == null) {
                    return null;
                }
                try {
                    return syncServices.integration().id();
                } catch (RuntimeException e) {
                    return null;
                }
            }

            private String syncPhase() {
                if (syncServices == null) {
                    return "UNAVAILABLE";
                }
                try {
                    return syncServices.readiness().name();
                } catch (RuntimeException e) {
                    return "UNAVAILABLE";
                }
            }
        }
}

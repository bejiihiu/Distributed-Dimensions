package kz.bejiihiu.dimensions.velocity.command;

import kz.bejiihiu.dimensions.common.config.ConfigSource;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.LoadReport;
import kz.bejiihiu.dimensions.common.config.ConfigurationService;
import kz.bejiihiu.dimensions.velocity.DegradedReasons;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Production wiring between the plugin's live state and the {@code /ddproxy} command.
 * Read-only except for {@link #reload()}, which re-reads the configuration through the
 * common configuration service - the service keeps the last-good snapshot on failure, so a
 * broken file is never half-applied. When no fallback exists at all (e.g. the bundled
 * defaults fail env expansion on a stock host), the service throws and {@link #reload()}
 * reports the failure as an unhealthy report instead of letting the exception escape the
 * command. A clean reload re-enables routing with no restart.
 */
public final class PluginAdminServices implements DdProxyCommand.AdminServices {

    private final ConfigurationService configuration;
    private final TransferOperations operations;
    private final BooleanSupplier channelRegistered;
    private final Logger logger;

    public PluginAdminServices(ConfigurationService configuration,
                               TransferOperations operations,
                               BooleanSupplier channelRegistered,
                               Logger logger) {
        this.configuration = configuration;
        this.operations = operations;
        this.channelRegistered = channelRegistered;
        this.logger = logger;
    }

    @Override
    public boolean channelRegistered() {
        return channelRegistered.getAsBoolean();
    }

    @Override
    public boolean configHealthy() {
        return configuration.isHealthy();
    }

    @Override
    public String configHealthSummary() {
        LoadReport report = configuration.report();
        var health = configuration.health();
        if (health.isEmpty()) {
            if (!configuration.isHealthy()) {
                // a failure so early the service never recorded live health details (boot log has it)
                return "configuration failed to load at boot; the proxy is in degraded mode (see the boot log)";
            }
            String source = report == null ? "config.yml" : sourceName(report.source());
            String version = report == null ? "?" : String.valueOf(report.configVersion());
            String suffix = report != null && !report.warnings().isEmpty()
                    ? " with " + report.warnings().size() + " unknown-key warning(s)" : "";
            return "loaded from " + source + ", config-version " + version + suffix;
        }
        List<String> extraNotes = nonDuplicateNotes(report, health.get());
        String notes = extraNotes.isEmpty() ? "" : " (" + String.join("; ", extraNotes) + ")";
        // health messages never contain secrets (configuration service contract)
        return health.get().getMessage() + notes;
    }

    @Override
    public List<String> dimensionLines() {
        DimensionsSettings dimensions = configuration.config().network().dimensions();
        List<String> lines = new ArrayList<>(3);
        lines.add(line("overworld", dimensions.overworld()));
        lines.add(line("nether", dimensions.nether()));
        lines.add(line("end", dimensions.end()));
        return lines;
    }

    @Override
    public int inFlightOperations() {
        return operations.pendingSize();
    }

    @Override
    public int pendingCapacity() {
        return TransferOperations.PENDING_CAPACITY;
    }

    @Override
    public int completedCacheSize() {
        return operations.completedSize();
    }

    @Override
    public long completedCacheTtlMillis() {
        return TransferOperations.DEFAULT_TTL.toMillis();
    }

    @Override
    public Map<String, Long> counters() {
        return operations.stats().snapshot();
    }

    @Override
    public List<TransferOperations.RecentOperation> recentTransfers(int n) {
        return operations.recent(n);
    }

    @Override
    public DdProxyCommand.ReloadReport reload() {
        try {
            configuration.load(); // the service itself decides: apply or keep last-good
        } catch (RuntimeException e) {
            // no last-good exists and even the bundled-defaults fallback failed (e.g. an
            // unset ${VAR}) - report the failure, never throw out of the command
            String sanitized = DegradedReasons.sanitize(e.getMessage());
            logger.error("/ddproxy reload: configuration failed to load: {} - transfers remain disabled", sanitized);
            List<String> details = new ArrayList<>();
            details.add("error: " + sanitized);
            LoadReport report = configuration.report();
            if (report != null) {
                details.add("source: " + sourceName(report.source()) + ", config-version " + report.configVersion());
                report.notes().forEach(details::add);
            }
            return new DdProxyCommand.ReloadReport(false, details);
        }
        LoadReport report = configuration.report();
        boolean healthy = configuration.isHealthy();
        List<String> details = new ArrayList<>();
        if (report != null) {
            details.add("source: " + sourceName(report.source()) + ", config-version " + report.configVersion());
            report.notes().forEach(details::add);
            if (!report.warnings().isEmpty()) {
                details.add("unknown keys ignored: " + String.join(", ", report.warnings()));
            }
        }
        configuration.health().ifPresent(error -> details.add("error: " + error.getMessage()));
        if (healthy) {
            logger.info("configuration reloaded on /ddproxy reload - routing re-enabled");
        } else {
            logger.error("/ddproxy reload: configuration is broken - keeping the last-good configuration");
        }
        return new DdProxyCommand.ReloadReport(healthy, details);
    }

    private static String line(String slot, kz.bejiihiu.dimensions.common.config.DimensionTarget target) {
        return slot + " -> server '" + target.server() + "' (" + target.dimension() + ")";
    }

    /** Notes from the report that do not merely echo the health message itself. */
    private static List<String> nonDuplicateNotes(LoadReport report, kz.bejiihiu.dimensions.common.config.ConfigurationException health) {
        if (report == null) {
            return List.of();
        }
        String message = health.getMessage();
        return report.notes().stream()
                .filter(note -> !note.equals(message)
                        && !note.equals("fell back to bundled defaults: " + message)
                        && !note.equals("kept last-good configuration: " + message))
                .toList();
    }

    private static String sourceName(ConfigSource source) {
        return switch (source) {
            case FILE -> "config.yml";
            case LAST_GOOD -> "last-good snapshot";
            case DEFAULTS_FALLBACK -> "bundled defaults (broken file, never loaded before)";
        };
    }
}

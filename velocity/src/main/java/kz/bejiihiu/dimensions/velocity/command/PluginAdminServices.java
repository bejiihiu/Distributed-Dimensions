package kz.bejiihiu.dimensions.velocity.command;

import kz.bejiihiu.dimensions.common.config.ConfigSource;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.config.LoadReport;
import kz.bejiihiu.dimensions.common.config.ConfigurationService;
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
 * broken file is never half-applied.
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
            String source = report == null ? "config.yml" : sourceName(report.source());
            String version = report == null ? "?" : String.valueOf(report.configVersion());
            String suffix = report != null && !report.warnings().isEmpty()
                    ? " with " + report.warnings().size() + " unknown-key warning(s)" : "";
            return "loaded from " + source + ", config-version " + version + suffix;
        }
        String notes = report == null || report.notes().isEmpty() ? "" : " (" + String.join("; ", report.notes()) + ")";
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
        configuration.load(); // the service itself decides: apply or keep last-good
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
            logger.info("configuration reloaded on /ddproxy reload");
        } else {
            logger.error("/ddproxy reload: configuration is broken - keeping the last-good configuration");
        }
        return new DdProxyCommand.ReloadReport(healthy, details);
    }

    private static String line(String slot, kz.bejiihiu.dimensions.common.config.DimensionTarget target) {
        return slot + " -> server '" + target.server() + "' (" + target.dimension() + ")";
    }

    private static String sourceName(ConfigSource source) {
        return switch (source) {
            case FILE -> "config.yml";
            case LAST_GOOD -> "last-good snapshot";
            case DEFAULTS_FALLBACK -> "bundled defaults (broken file, never loaded before)";
        };
    }
}

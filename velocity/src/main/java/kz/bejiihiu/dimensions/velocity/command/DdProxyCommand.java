package kz.bejiihiu.dimensions.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Native admin command for the proxy half: {@code /ddproxy status|reload|transfers [n]}.
 * No cloud framework - the surface is small enough to keep in one class, and every piece of
 * information comes from a services callback so the command stays testable without a proxy.
 *
 * <p>Permission: {@value #PERMISSION} (default: console; grant to operators on servers with a
 * permission plugin).
 */
public final class DdProxyCommand implements SimpleCommand {

    public static final String PERMISSION = "distributeddimensions.admin";
    public static final int DEFAULT_TRANSFER_ROWS = 10;
    public static final int MAX_TRANSFER_ROWS = 50;

    /**
     * Everything the command needs from the live plugin, behind one interface. Production
     * builds it in the plugin class; tests provide a programmable fake.
     */
    public interface AdminServices {
        /** True once the channel registration finished successfully at boot. */
        boolean channelRegistered();

        /** Current configuration health (never throws). */
        boolean configHealthy();

        /** One-line human summary of the configuration state (healthy or last error). */
        String configHealthSummary();

        /** "name -> server 'server' (dimension)" lines; only meaningful while config is healthy. */
        List<String> dimensionLines();

        int inFlightOperations();

        int pendingCapacity();

        int completedCacheSize();

        long completedCacheTtlMillis();

        /** Stable-order counters for status output. */
        Map<String, Long> counters();

        /** Newest first; at most {@code n} rows (caller clamps). */
        List<TransferOperations.RecentOperation> recentTransfers(int n);

        /** Re-reads the configuration from disk; keeps last-good on failure. Never throws. */
        ReloadReport reload();
    }

    public record ReloadReport(boolean healthy, List<String> details) {
    }

    private final AdminServices services;

    public DdProxyCommand(AdminServices services) {
        this.services = services;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof ConsoleCommandSource || invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("status", "reload", "transfers");
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("status", "reload", "transfers")
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("transfers")) {
            return List.of("10", "25", "50");
        }
        return List.of();
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        if (!hasPermission(invocation)) {
            source.sendMessage(Component.text("You don't have permission to use this command (" + PERMISSION + ").", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(source);
            case "reload" -> reload(source);
            case "transfers" -> transfers(source, args);
            default -> usage(source);
        }
    }

    // ------------------------------------------------------------------ subcommands

    private void status(Audience source) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Distributed Dimensions - proxy status", NamedTextColor.YELLOW));

        if (services.configHealthy()) {
            lines.add(Component.text("config: healthy (" + services.configHealthSummary() + ")", NamedTextColor.GREEN));
        } else {
            lines.add(Component.text("config: BROKEN - " + services.configHealthSummary(), NamedTextColor.RED));
            lines.add(Component.text("  transfers are DISABLED until '/ddproxy reload' reports a clean load", NamedTextColor.RED));
        }

        lines.add(Component.text("channel: distributeddimensions:transfer ("
                + (services.channelRegistered() ? "registered" : "NOT REGISTERED") + ")",
                services.channelRegistered() ? NamedTextColor.GREEN : NamedTextColor.RED));

        if (services.configHealthy()) {
            lines.add(Component.text("dimensions:", NamedTextColor.YELLOW));
            for (String line : services.dimensionLines()) {
                lines.add(Component.text("  " + line, NamedTextColor.GRAY));
            }
        } else {
            lines.add(Component.text("dimensions: unavailable (configuration is broken)", NamedTextColor.RED));
        }

        lines.add(Component.text("transfers: " + services.inFlightOperations() + " in flight (pending capacity "
                + services.pendingCapacity() + "), " + services.completedCacheSize()
                + " outcome replays cached (ttl " + (services.completedCacheTtlMillis() / 60_000) + "m)", NamedTextColor.GRAY));

        lines.add(Component.text("counters:", NamedTextColor.YELLOW));
        services.counters().forEach((key, value) ->
                lines.add(Component.text("  " + key + " = " + value, NamedTextColor.GRAY)));

        for (Component line : lines) {
            source.sendMessage(line);
        }
    }

    private void reload(Audience source) {
        ReloadReport report = services.reload();
        if (report.healthy()) {
            source.sendMessage(Component.text("Configuration reloaded.", NamedTextColor.GREEN));
        } else {
            source.sendMessage(Component.text("Reload failed - keeping the last-good configuration.", NamedTextColor.RED));
        }
        for (String detail : report.details()) {
            source.sendMessage(Component.text("  " + detail, NamedTextColor.GRAY));
        }
        if (!report.healthy()) {
            source.sendMessage(Component.text(
                    "  transfers remain DISABLED until a clean load", NamedTextColor.RED));
        }
    }

    private void transfers(Audience source, String[] args) {
        int n = DEFAULT_TRANSFER_ROWS;
        if (args.length >= 2) {
            try {
                n = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                source.sendMessage(Component.text("'/ddproxy transfers [n]' expects a number, got '" + args[1] + "'.", NamedTextColor.RED));
                return;
            }
            if (n <= 0) {
                source.sendMessage(Component.text("'n' must be positive.", NamedTextColor.RED));
                return;
            }
        }
        boolean clamped = n > MAX_TRANSFER_ROWS;
        n = Math.min(n, MAX_TRANSFER_ROWS);

        List<TransferOperations.RecentOperation> ops = services.recentTransfers(n);
        source.sendMessage(Component.text("Recent transfers (" + ops.size() + (clamped ? ", capped at " + MAX_TRANSFER_ROWS : "") + "):", NamedTextColor.YELLOW));
        if (ops.isEmpty()) {
            source.sendMessage(Component.text("  (none yet)", NamedTextColor.GRAY));
            return;
        }
        DateTimeFormatter time = DateTimeFormatter.ISO_INSTANT;
        for (TransferOperations.RecentOperation op : ops) {
            final String s = op.error() != null ? " " + op.error().wireName() : "";
            String result = switch (op.kind()) {
                case SUCCESS -> "SUCCESS";
                case FAILED -> "FAILED" + s;
                case ABANDONED -> "ABANDONED" + s;
            };
            String line = time.format(op.finishedAt()) + "  " + op.requestId().toString().substring(0, 8)
                    + "  " + op.playerUuid() + "  " + op.sourceServer() + " -> " + op.targetServer()
                    + "  " + result + "  " + op.durationMillis() + "ms";
            source.sendMessage(Component.text("  " + line, op.kind() == TransferOperations.OutcomeKind.SUCCESS
                    ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (op.message() != null) {
                source.sendMessage(Component.text("      " + op.message(), NamedTextColor.DARK_GRAY));
            }
        }
    }

    private void usage(Audience source) {
        source.sendMessage(Component.text("Usage: /ddproxy <status|reload|transfers [n]>", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  status     - configuration health, channel, dimensions, in-flight operations, counters", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  reload     - re-read config.yml; keeps the last-good configuration on failure", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  transfers [n] - last n completed/failed/abandoned operations (default 10, max " + MAX_TRANSFER_ROWS + ")", NamedTextColor.GRAY));
    }
}

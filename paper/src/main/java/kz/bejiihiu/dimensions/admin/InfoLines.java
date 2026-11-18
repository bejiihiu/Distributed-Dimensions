package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the {@code /dd info} output lines from an {@link AdminInfo} snapshot and
 * the active {@link Messages} selection. Pure: no Bukkit, no services, no clock —
 * every fact arrives inside {@link AdminInfo}.
 */
public final class InfoLines {

    private InfoLines() {
    }

    public static List<Component> build(AdminInfo info, Messages messages) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(messages, "messages");

        List<Component> lines = new ArrayList<>();
        lines.add(messages.render("brand.header", Placeholder.unparsed("version", info.pluginVersion())));
        lines.add(messages.render("info.server", Placeholder.unparsed("version", info.serverVersion())));

        boolean healthy = "HEALTHY".equals(info.healthStatus());
        if (healthy) {
            lines.add(messages.render("info.health.ok"));
        } else {
            lines.add(messages.render("info.health.degraded"));
            for (String reason : info.healthReasons()) {
                lines.add(messages.render("info.health.reason", Placeholder.unparsed("reason", reason)));
            }
        }

        lines.add(messages.render("info.config.source",
                Placeholder.unparsed("source", info.configSource()),
                Placeholder.unparsed("file-created", Boolean.toString(info.configFileCreated()))));
        String migration = info.configMigrated()
                ? "v" + info.migratedFrom() + " -> v" + info.migratedTo()
                : "none";
        lines.add(messages.render("info.config.version",
                Placeholder.unparsed("version", Integer.toString(info.configVersion())),
                Placeholder.unparsed("migration", migration)));
        lines.add(messages.render("info.database",
                Placeholder.unparsed("engine", info.databaseEngine()),
                Placeholder.unparsed("pool-max", Integer.toString(info.poolMaximum())),
                Placeholder.unparsed("pool-min", Integer.toString(info.poolMinimum())),
                Placeholder.unparsed("prefix", info.tablePrefix())));

        lines.add(messages.render("info.dimensions.header"));
        for (AdminInfo.DimensionRoute route : info.dimensions()) {
            lines.add(messages.render("info.dimension.entry",
                    Placeholder.unparsed("dimension", route.dimension()),
                    Placeholder.unparsed("server", route.server())));
        }

        if (info.mirageEnabled()) {
            lines.add(messages.render("info.mirage",
                    Placeholder.unparsed("count", Integer.toString(info.mirageTrackedWorlds()))));
            if (info.mirageFailures() > 0) {
                lines.add(messages.render("info.mirage.degraded",
                        Placeholder.unparsed("failures", Integer.toString(info.mirageFailures()))));
            }
        } else {
            lines.add(messages.render("info.mirage.disabled"));
        }

        if (info.syncStrategy() == null || info.syncStrategy().isBlank()) {
            lines.add(messages.render("info.sync.none"));
        } else {
            lines.add(messages.render("info.sync",
                    Placeholder.unparsed("level", info.syncStrategy()),
                    Placeholder.unparsed("phase", info.syncPhase()),
                    Placeholder.unparsed("wait-for-sync", Boolean.toString(info.syncWaitFor()))));
        }

        lines.add(messages.render("info.api",
                Placeholder.unparsed("enabled", Boolean.toString(info.apiEnabled()))));
        lines.add(messages.render("info.compat",
                Placeholder.unparsed("synthetic-events", Boolean.toString(info.compatSyntheticEvents())),
                Placeholder.unparsed("grant-advancements", Boolean.toString(info.grantAdvancements()))));
        lines.add(messages.render("info.uptime",
                Placeholder.unparsed("duration", humanDuration(info.uptimeMillis()))));
        return lines;
    }

    /** Compact human-readable duration: {@code 3d 2h 14m 5s}. */
    static String humanDuration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long totalSeconds = millis / 1000;
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append('d').append(' ');
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append('h').append(' ');
        }
        if (days > 0 || hours > 0 || minutes > 0) {
            sb.append(minutes).append('m').append(' ');
        }
        sb.append(seconds).append('s');
        return sb.toString().trim();
    }
}

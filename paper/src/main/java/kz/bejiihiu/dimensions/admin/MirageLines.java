package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the {@code /dd mirage} output lines from a {@link MirageInfo} snapshot.
 * Pure: no Bukkit world scan — the snapshot comes from the mirage registry, which
 * is the single source of truth for which worlds the plugin created or adopted.
 */
public final class MirageLines {

    private MirageLines() {
    }

    public static List<Component> build(MirageInfo info, Messages messages) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(messages, "messages");

        List<Component> lines = new ArrayList<>();
        if (!info.enabled()) {
            lines.add(messages.render("mirage.disabled"));
            return lines;
        }

        long created = info.entries().stream().filter(MirageInfo.Entry::createdByPlugin).count();
        long real = info.entries().size() - created;
        lines.add(messages.render("mirage.header",
                Placeholder.unparsed("count", Integer.toString(info.entries().size()))));
        for (MirageInfo.Entry entry : info.entries()) {
            String kind = entry.createdByPlugin()
                    ? messages.raw("mirage.kind.created")
                    : messages.raw("mirage.kind.real");
            // kind values come from our own bundle and carry their own markup, so they
            // are parsed, not unparsed — every other placeholder stays unparsed.
            lines.add(messages.render("mirage.entry",
                    Placeholder.unparsed("name", entry.name()),
                    Placeholder.unparsed("environment", entry.environment()),
                    Placeholder.unparsed("dimension", entry.dimension()),
                    Placeholder.parsed("kind", kind)));
        }
        lines.add(messages.render("mirage.summary",
                Placeholder.unparsed("created", Long.toString(created)),
                Placeholder.unparsed("real", Long.toString(real))));
        for (String failure : info.failures()) {
            lines.add(messages.render("mirage.failure",
                    Placeholder.unparsed("failure", failure)));
        }
        return lines;
    }
}

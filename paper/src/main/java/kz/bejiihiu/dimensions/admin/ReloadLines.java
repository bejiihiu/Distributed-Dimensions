package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the {@code /dd reload} output lines from a {@link ReloadOutcome}.
 * Pure: no services, no file access — every fact arrives in the record.
 */
public final class ReloadLines {

    private ReloadLines() {
    }

    public static List<Component> build(ReloadOutcome outcome, Messages messages) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(messages, "messages");

        List<Component> lines = new ArrayList<>();
        if (!outcome.healthy()) {
            lines.add(messages.render("reload.failed",
                    Placeholder.unparsed("reason", outcome.failureReason())));
            lines.add(messages.render("reload.failed.hint"));
            return lines;
        }

        if (outcome.changedSections().isEmpty()) {
            lines.add(messages.render("reload.ok.clean",
                    Placeholder.unparsed("version", Integer.toString(outcome.configVersion()))));
        } else {
            lines.add(messages.render("reload.ok.changed",
                    Placeholder.unparsed("version", Integer.toString(outcome.configVersion())),
                    Placeholder.unparsed("sections", String.join(", ", outcome.changedSections()))));
        }
        if (outcome.migratedFrom() >= 0 && outcome.migratedTo() >= 0) {
            lines.add(messages.render("reload.migration",
                    Placeholder.unparsed("from", Integer.toString(outcome.migratedFrom())),
                    Placeholder.unparsed("to", Integer.toString(outcome.migratedTo()))));
        }
        if (outcome.newLanguage() != null) {
            lines.add(messages.render("reload.language",
                    Placeholder.unparsed("language", outcome.newLanguage())));
        }
        if (!outcome.warnings().isEmpty()) {
            lines.add(messages.render("reload.warnings",
                    Placeholder.unparsed("warnings", String.join(", ", outcome.warnings()))));
        }
        return lines;
    }
}

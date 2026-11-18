package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadLinesTest {

    private static Messages messages() {
        return new Messages(Messages.class.getClassLoader(), message -> { });
    }

    private static String joined(List<net.kyori.adventure.text.Component> lines) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        return lines.stream().map(serializer::serialize).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ReloadOutcome okClean() {
        return new ReloadOutcome(true, 2, "FILE", List.of(), "en", null,
                -1, -1, List.of(), null);
    }

    @Test
    void cleanReloadReportsNoChangesAndRestartReality() {
        String text = joined(ReloadLines.build(okClean(), messages()));
        assertTrue(text.contains("OK"));
        assertTrue(text.contains("v2"));
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("restart"),
                "clean report must say the rest requires a restart:\n" + text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("reloaded"),
                "the report must not claim a hot reload happened:\n" + text);
    }

    @Test
    void changedSectionsAreListedButNotApplied() {
        ReloadOutcome outcome = new ReloadOutcome(true, 2, "FILE",
                List.of("database", "mirage"), "en", null,
                -1, -1, List.of(), null);
        String text = joined(ReloadLines.build(outcome, messages()));
        assertTrue(text.contains("database, mirage"));
        assertTrue(text.contains("NOT applied"));
        assertTrue(text.contains("restart"));
    }

    @Test
    void languageChangeIsReportedAsApplied() {
        ReloadOutcome outcome = new ReloadOutcome(true, 2, "FILE",
                List.of("general"), "en", "ru",
                -1, -1, List.of(), null);
        String text = joined(ReloadLines.build(outcome, messages()));
        assertTrue(text.contains("language applied"), "language re-selection must be visible:\n" + text);
        assertTrue(text.contains("ru"));
    }

    @Test
    void migrationIsReported() {
        ReloadOutcome outcome = new ReloadOutcome(true, 2, "FILE",
                List.of("config-version"), "en", null,
                1, 2, List.of(), null);
        String text = joined(ReloadLines.build(outcome, messages()));
        assertTrue(text.contains("v1"));
        assertTrue(text.contains("v2"));
    }

    @Test
    void warningsAreReported() {
        ReloadOutcome outcome = new ReloadOutcome(true, 2, "FILE",
                List.of(), "en", null,
                -1, -1, List.of("database.bogus-key"), null);
        String text = joined(ReloadLines.build(outcome, messages()));
        assertTrue(text.contains("database.bogus-key"));
    }

    @Test
    void failureOutcomeKeepsBackendNarrative() {
        ReloadOutcome outcome = ReloadOutcome.failure(2, "LAST_GOOD",
                "mapping YAML to PluginConfig failed at 'database.pool-size': expected int but was string");
        String text = joined(ReloadLines.build(outcome, messages()));
        assertTrue(text.contains("FAILED"));
        assertTrue(text.contains("database.pool-size"));
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("last-good"));
    }

    @Test
    void russianFailureOutcomeIsLocalized() {
        Messages messages = messages();
        messages.select("ru");
        ReloadOutcome outcome = ReloadOutcome.failure(-1, "DEFAULTS_FALLBACK", "broken yaml");
        String text = joined(ReloadLines.build(outcome, messages));
        assertTrue(text.contains("НЕ ПРОЙДЕНА"));
        assertTrue(text.contains("broken yaml"));
    }
}

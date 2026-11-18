package kz.bejiihiu.dimensions.common.config;

import java.util.List;

/**
 * What happened during {@link ConfigurationService#load()}. Platforms should log
 * {@link #warnings()} and {@link #notes()} at WARN/INFO — they never carry secret
 * values, only dotted key paths and free-form messages.
 *
 * @param source      where the effective configuration came from
 * @param fileCreated true when the bundled defaults were written to disk by this load
 * @param configVersion the schema version in effect (after migrations)
 * @param migratedFrom version found in the file before migrations, or {@code -1} when none ran
 * @param migratedTo  version after migrations, or {@code -1} when none ran
 * @param warnings    unknown keys, as dotted paths ("database.bogus-key"); ignored, not fatal
 * @param notes       informational messages (migrations applied, defaults written, …)
 */
public record LoadReport(
        ConfigSource source,
        boolean fileCreated,
        int configVersion,
        int migratedFrom,
        int migratedTo,
        List<String> warnings,
        List<String> notes) {

    public LoadReport {
        warnings = List.copyOf(warnings);
        notes = List.copyOf(notes);
    }
}

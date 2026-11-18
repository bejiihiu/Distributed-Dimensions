package kz.bejiihiu.dimensions.admin;

import java.util.List;

/**
 * The outcome of {@code /dd reload}: an honest report of what re-reading
 * {@code config.yml} did, without claiming anything was swapped in-place.
 *
 * <p>Semantics (see docs/commands.md):
 * <ul>
 *   <li>{@code healthy=false} — the file could not be loaded even with last-good
 *       fallback; the running backend is untouched and {@code failureReason} says why.</li>
 *   <li>{@code healthy=true} — the file loaded (migrations, if any, were applied to the
 *       file atomically); {@code changedSections} lists what differs from the running
 *       configuration. Only {@code general.language} is applied live; everything else
 *       requires a restart. {@code newLanguage} is non-null exactly when the language
 *       was re-selected.</li>
 * </ul>
 */
public record ReloadOutcome(
        boolean healthy,
        int configVersion,
        String configSource,
        List<String> changedSections,
        String previousLanguage,
        String newLanguage,
        int migratedFrom,
        int migratedTo,
        List<String> warnings,
        String failureReason) {

    public ReloadOutcome {
        changedSections = List.copyOf(changedSections == null ? List.of() : changedSections);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static ReloadOutcome failure(int configVersion, String configSource, String reason) {
        return new ReloadOutcome(false, configVersion, configSource, List.of(),
                null, null, -1, -1, List.of(), reason == null ? "unknown configuration failure" : reason);
    }
}

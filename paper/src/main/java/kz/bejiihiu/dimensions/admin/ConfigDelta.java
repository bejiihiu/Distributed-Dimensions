package kz.bejiihiu.dimensions.admin;

import kz.bejiihiu.dimensions.common.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Field-by-field diff of two configuration snapshots at section granularity.
 * Used by {@code /dd reload} to report what a re-read of {@code config.yml}
 * would change relative to the configuration the backend is actually running.
 *
 * <p>Only {@code general.language} is hot-applicable: the message bundle is
 * re-selected in place when it changes. Every other section is constructor-wired
 * into the running backend and cannot be swapped without a restart — a change
 * is reported, never silently applied.
 *
 * <p>The diff compares records structurally; no values are printed, so secrets
 * never reach the report.
 */
public final class ConfigDelta {

    private ConfigDelta() {
    }

    /** The result of comparing the running snapshot against a freshly loaded one. */
    public record Result(
            /** Changed top-level sections (and possibly {@code config-version}); empty when identical. */
            List<String> changedSections,
            /** True when {@code general.language} differs between the two snapshots. */
            boolean languageChanged,
            /** The language of the running snapshot. */
            String previousLanguage,
            /** The language of the freshly loaded snapshot. */
            String newLanguage) {

        public Result {
            changedSections = List.copyOf(changedSections == null ? List.of() : changedSections);
        }

        public boolean hasChanges() {
            return !changedSections.isEmpty();
        }
    }

    /**
     * Compares {@code running} (the snapshot in effect) with {@code reloaded}
     * (a fresh load). Never prints values; only section names and the language
     * pair are exposed.
     */
    public static Result diff(PluginConfig running, PluginConfig reloaded) {
        Objects.requireNonNull(running, "running");
        Objects.requireNonNull(reloaded, "reloaded");

        List<String> changed = new ArrayList<>();
        if (running.configVersion() != reloaded.configVersion()) {
            changed.add("config-version");
        }
        if (!running.general().equals(reloaded.general())) {
            changed.add("general");
        }
        if (!running.network().equals(reloaded.network())) {
            changed.add("network");
        }
        if (!running.portals().equals(reloaded.portals())) {
            changed.add("portals");
        }
        if (!running.entities().equals(reloaded.entities())) {
            changed.add("entities");
        }
        if (!running.api().equals(reloaded.api())) {
            changed.add("api");
        }
        if (!running.sync().equals(reloaded.sync())) {
            changed.add("sync");
        }
        if (!running.redis().equals(reloaded.redis())) {
            changed.add("redis");
        }
        if (!running.database().equals(reloaded.database())) {
            changed.add("database");
        }
        if (!running.debug().equals(reloaded.debug())) {
            changed.add("debug");
        }
        if (!running.mirage().equals(reloaded.mirage())) {
            changed.add("mirage");
        }

        boolean languageChanged = !running.general().language().equals(reloaded.general().language());
        return new Result(changed, languageChanged,
                running.general().language(), reloaded.general().language());
    }
}

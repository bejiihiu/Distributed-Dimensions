package kz.bejiihiu.dimensions.admin;

import java.util.List;

/**
 * Everything {@code /dd mirage} reports. {@code enabled=false} means
 * {@code mirage.enabled=false} in the running configuration — the rest of the
 * record is then empty and the output is a single explanatory line.
 */
public record MirageInfo(
        boolean enabled,
        List<Entry> entries,
        List<String> failures) {

    public MirageInfo {
        entries = List.copyOf(entries == null ? List.of() : entries);
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    /** One tracked world, mirage or the real world occupying its slot. */
    public record Entry(
            String name,
            String environment,
            String dimension,
            boolean createdByPlugin) {
    }
}

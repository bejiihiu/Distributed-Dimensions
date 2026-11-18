package kz.bejiihiu.dimensions.common.config;

/** Canonical mirage world names, by Bukkit level-name convention. */
public record MirageNamesSettings(String overworld, String nether, String end) {

    public MirageNamesSettings {
        overworld = requireName("mirage.names.overworld", overworld);
        nether = requireName("mirage.names.nether", nether);
        end = requireName("mirage.names.end", end);
    }

    private static String requireName(String path, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return value.trim();
    }
}

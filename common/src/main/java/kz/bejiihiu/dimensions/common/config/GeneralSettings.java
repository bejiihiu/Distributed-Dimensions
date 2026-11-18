package kz.bejiihiu.dimensions.common.config;

/**
 * Global switches. {@code language} selects the language used for user-facing
 * plugin messages ("en"/"ru" today); {@code strictVanillaMode} restricts the
 * plugin to assisting the vanilla flow instead of replacing it.
 */
public record GeneralSettings(String language, boolean strictVanillaMode) {

    public GeneralSettings {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
    }
}

package kz.bejiihiu.dimensions.common.config;

/**
 * Global switches. {@code language} selects the language used for user-facing
 * plugin messages ("en"/"ru" today).
 */
public record GeneralSettings(String language) {

    public GeneralSettings {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
    }
}

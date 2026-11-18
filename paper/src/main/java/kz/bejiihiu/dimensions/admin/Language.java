package kz.bejiihiu.dimensions.admin;

import java.util.Locale;
import java.util.Optional;

/**
 * The languages the bundled message resources support. The plugin ships
 * {@code en} and {@code ru}; any other configured code falls back to
 * {@link #EN} with a single warning at selection time.
 */
public enum Language {

    EN("en"),
    RU("ru");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Case-insensitive lookup of a language by its configured code. Accepts
     * language tags such as {@code "ru-RU"} by looking at the primary subtag.
     * Returns empty for unsupported codes — the caller decides how to fall back.
     */
    public static Optional<Language> fromCode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        int dash = normalized.indexOf('-');
        if (dash > 0) {
            normalized = normalized.substring(0, dash);
        }
        for (Language language : values()) {
            if (language.code.equals(normalized)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }
}

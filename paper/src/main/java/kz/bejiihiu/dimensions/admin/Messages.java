package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The plugin's user-facing message bundles for the admin command surface.
 *
 * <p>Bundles are {@code .properties} files at {@code admin/messages_<lang>.properties}
 * on the classpath, read as UTF-8 (the default for {@link Properties#load(java.io.Reader)}
 * since Java 9). Values are <a href="https://docs.advntr.dev/minimessage/">MiniMessage</a>
 * markup; values containing {@code <...>} that must render literally escape it as
 * {@code \<}. Runtime values are always substituted with
 * {@link Placeholder#unparsed(String, String)} so markup in data is rendered as text.
 *
 * <p><b>Fail-fast at boot.</b> The two bundles must define the exact same key set and no
 * bundle may be empty; a mismatch throws at construction rather than rendering a broken
 * half-localized message mid-command later. The admin surface treats that as a build
 * defect: it logs SEVERE and leaves {@code /dd} unregistered instead of crashing the
 * whole backend.
 *
 * <p><b>Selection.</b> {@link #select(String)} maps the configured
 * {@code general.language} onto a bundle. {@code "ru"} selects Russian; anything else
 * selects English. An unsupported code falls back to English with a warning, emitted at
 * most once per distinct offending value per instance — a fixed typo warns once, not on
 * every boot's worth of reloads. Selection is hot-swappable: the admin surface re-selects
 * on {@code /dd reload}.
 */
public final class Messages {

    /** Classpath prefix of the bundles; the language code plus {@code .properties} is appended. */
    public static final String BUNDLE_PATH_PREFIX = "admin/messages_";

    /** Fallback bundle used when the configured language is unsupported. */
    public static final Language DEFAULT_LANGUAGE = Language.EN;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Map<Language, Properties> bundles;
    private final Consumer<String> warnSink;
    private final Object warnLock = new Object();
    private volatile Language selected = DEFAULT_LANGUAGE;
    private String lastWarnedLanguage;

    public Messages(ClassLoader loader, Consumer<String> warnSink) {
        Objects.requireNonNull(loader, "loader");
        this.warnSink = warnSink == null ? message -> { } : warnSink;
        EnumMap<Language, Properties> loaded = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            loaded.put(language, loadBundle(loader, language));
        }
        requireMatchingKeys(loaded);
        this.bundles = Map.copyOf(loaded);
    }

    // ------------------------------------------------------------------ selection

    /**
     * Selects the bundle from the configured language code. Falls back to
     * {@link #DEFAULT_LANGUAGE} for unsupported codes, warning at most once per
     * distinct offending value.
     */
    public void select(String configuredLanguage) {
        Optional<Language> resolved = Language.fromCode(configuredLanguage);
        if (resolved.isPresent()) {
            this.selected = resolved.get();
            return;
        }
        warnOncePerOffendingValue(configuredLanguage);
        this.selected = DEFAULT_LANGUAGE;
    }

    /** The bundle currently selected. */
    public Language selectedLanguage() {
        return selected;
    }

    private void warnOncePerOffendingValue(String configuredLanguage) {
        String offending = configuredLanguage == null ? "<null>" : configuredLanguage.trim();
        synchronized (warnLock) {
            if (offending.equals(lastWarnedLanguage)) {
                return;
            }
            lastWarnedLanguage = offending;
        }
        warnSink.accept("general.language='" + offending + "' is not supported (supported: en, ru) — falling back to '"
                + DEFAULT_LANGUAGE.code() + "'");
    }

    // ------------------------------------------------------------------ lookup

    /** The raw (still markup-bearing) bundle value. Throws for a missing key. */
    public String raw(String key) {
        return properties().getProperty(Objects.requireNonNull(key, "key"));
    }

    /** True when the selected bundle defines {@code key}. */
    public boolean has(String key) {
        String value = raw(key);
        return value != null;
    }

    /** Keys of the given bundle; used by the bundle parity checks and tests. */
    public Set<String> keys(Language language) {
        Properties props = bundles.get(Objects.requireNonNull(language, "language"));
        Set<String> keys = new TreeSet<>();
        for (String name : props.stringPropertyNames()) {
            keys.add(name);
        }
        return Set.copyOf(keys);
    }

    // ------------------------------------------------------------------ rendering

    /** Renders {@code key} as a component. Throws for a missing key. */
    public Component render(String key) {
        String value = requireValue(key);
        return MINI_MESSAGE.deserialize(value);
    }

    /**
     * Renders {@code key} with the given tag resolvers. Callers should pass
     * {@link Placeholder#unparsed(String, String)} for data-bearing values so
     * markup inside data renders as text, never as tags.
     */
    public Component render(String key, TagResolver... resolvers) {
        String value = requireValue(key);
        return MINI_MESSAGE.deserialize(value, resolvers);
    }

    // ------------------------------------------------------------------ internals

    private String requireValue(String key) {
        Objects.requireNonNull(key, "key");
        String value = properties().getProperty(key);
        if (value == null) {
            throw new IllegalStateException("missing message key '" + key + "' in the '"
                    + selected.code() + "' bundle");
        }
        return value;
    }

    private Properties properties() {
        return bundles.get(selected);
    }

    private static Properties loadBundle(ClassLoader loader, Language language) {
        String path = BUNDLE_PATH_PREFIX + language.code() + ".properties";
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled message resource: " + path);
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (props.isEmpty()) {
                throw new IllegalStateException("bundled message resource is empty: " + path);
            }
            return props;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read bundled message resource " + path + ": " + e.getMessage(), e);
        }
    }

    private static void requireMatchingKeys(Map<Language, Properties> loaded) {
        Set<String> reference = null;
        Language referenceLanguage = null;
        for (Map.Entry<Language, Properties> entry : loaded.entrySet()) {
            Set<String> keys = new TreeSet<>(entry.getValue().stringPropertyNames());
            if (reference == null) {
                reference = keys;
                referenceLanguage = entry.getKey();
                continue;
            }
            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(keys);
            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(reference);
            if (!missing.isEmpty() || !extra.isEmpty()) {
                throw new IllegalStateException("message bundles are out of sync: "
                        + entry.getKey().code() + " is missing " + missing + ", has extra " + extra
                        + " (reference: " + referenceLanguage.code() + ")");
            }
        }
    }
}

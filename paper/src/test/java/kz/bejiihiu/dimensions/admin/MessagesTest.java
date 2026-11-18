package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static Messages newMessages() {
        return newMessages(new ArrayList<>());
    }

    private static Messages newMessages(List<String> warnings) {
        return new Messages(Messages.class.getClassLoader(), warnings::add);
    }

    @Test
    void bothBundlesLoadAndDefineTheSameKeys() {
        Messages messages = newMessages();
        Set<String> en = messages.keys(Language.EN);
        Set<String> ru = messages.keys(Language.RU);
        assertTrue(en.size() > 10, "bundle should carry a meaningful key set");
        assertEquals(en, ru, "en and ru bundles must define exactly the same keys");
    }

    @Test
    void everyBundleKeyRendersToNonEmptyPlainText() {
        Messages messages = newMessages();
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        // feed a dummy unparsed resolver for every tag-shaped token in the value, so
        // parameterized keys render without their real arguments; structural tags get
        // shadowed harmlessly — this test only asserts non-empty output
        java.util.regex.Pattern tags = java.util.regex.Pattern.compile("<[a-z0-9_-]+>");
        for (Language language : Language.values()) {
            messages.select(language.code());
            for (String key : messages.keys(language)) {
                String raw = messages.raw(key);
                java.util.regex.Matcher matcher = tags.matcher(raw);
                List<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> resolvers = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (matcher.find()) {
                    String name = matcher.group().substring(1, matcher.group().length() - 1);
                    if (seen.add(name)) {
                        resolvers.add(net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(name, "x"));
                    }
                }
                Component rendered = resolvers.isEmpty()
                        ? messages.render(key)
                        : messages.render(key, resolvers.toArray(new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[0]));
                String text = serializer.serialize(rendered).trim();
                assertTrue(text.length() > 0, "key '" + key + "' renders empty in " + language);
            }
        }
    }

    @Test
    void languageSelectionIsCaseAndTagInsensitive() {
        Messages messages = newMessages();
        messages.select("RU");
        assertEquals(Language.RU, messages.selectedLanguage());
        messages.select("ru-RU");
        assertEquals(Language.RU, messages.selectedLanguage());
        messages.select("En");
        assertEquals(Language.EN, messages.selectedLanguage());
    }

    @Test
    void unknownLanguageFallsBackToEnglishWithASingleWarning() {
        List<String> warnings = new ArrayList<>();
        Messages messages = newMessages(warnings);
        messages.select("de");
        assertEquals(Language.EN, messages.selectedLanguage());
        assertEquals(1, warnings.size(), "the first unsupported code warns");
        messages.select("de");
        assertEquals(1, warnings.size(), "repeating the same unsupported code stays silent");
        messages.select("fr");
        assertEquals(2, warnings.size(), "a new offending code warns again");
    }

    @Test
    void nullAndBlankLanguagesFallBackToEnglishWithAWarning() {
        List<String> warnings = new ArrayList<>();
        Messages messages = newMessages(warnings);
        messages.select(null);
        assertEquals(Language.EN, messages.selectedLanguage());
        assertEquals(1, warnings.size());
        messages.select("   ");
        assertEquals(Language.EN, messages.selectedLanguage());
        assertEquals(2, warnings.size(), "blank is a distinct offending value and warns");
        messages.select("   ");
        assertEquals(2, warnings.size(), "repeating the blank value stays silent");
    }

    @Test
    void missingKeyThrows() {
        Messages messages = newMessages();
        assertThrows(IllegalStateException.class, () -> messages.render("no.such.key"));
    }

    @Test
    void unparsedPlaceholdersEscapeMarkupFromData() {
        Messages messages = newMessages();
        Component rendered = messages.render("no.permission",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("permission", "<b>not-a-tag</b>"));
        String text = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertTrue(text.contains("<b>not-a-tag</b>"), "data must render as literal text, got: " + text);
    }

    @Test
    void helpUsageShowsTheAngleBracketLiteral() {
        Messages messages = newMessages();
        String text = PlainTextComponentSerializer.plainText().serialize(messages.render("help.usage"));
        assertTrue(text.contains("<info|reload|mirage>"), "usage must show the subcommand list literally, got: " + text);
    }
}

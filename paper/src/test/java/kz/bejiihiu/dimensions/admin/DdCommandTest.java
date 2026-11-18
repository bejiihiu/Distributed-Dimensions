package kz.bejiihiu.dimensions.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DdCommandTest {

    @Test
    void noArgumentsSuggestAllSubcommands() {
        assertEquals(List.of("info", "reload", "mirage"), DdCommand.suggestSubcommands(new String[0]));
    }

    @Test
    void prefixFiltersSuggestionsCaseInsensitively() {
        assertEquals(List.of("info"), DdCommand.suggestSubcommands(new String[]{"in"}));
        assertEquals(List.of("reload"), DdCommand.suggestSubcommands(new String[]{"RE"}));
        assertEquals(List.of("info", "reload", "mirage"), DdCommand.suggestSubcommands(new String[]{""}));
    }

    @Test
    void secondArgumentSuggestsNothing() {
        assertEquals(List.of(), DdCommand.suggestSubcommands(new String[]{"info", "extra"}));
        assertEquals(List.of(), DdCommand.suggestSubcommands(new String[]{"reload", "now"}));
    }

    @Test
    void permissionNodeIsTheSharedAdminNode() {
        assertEquals("distributeddimensions.admin", DdCommand.PERMISSION);
    }

    @Test
    void subcommandsAreExactlyTheThreeDocumentedLiterals() {
        assertEquals(List.of("info", "reload", "mirage"), DdCommand.SUBCOMMANDS);
    }
}

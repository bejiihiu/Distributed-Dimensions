package kz.bejiihiu.dimensions.admin;

import kz.bejiihiu.dimensions.common.config.PluginConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDeltaTest {

    @Test
    void identicalSnapshotsReportNoChanges() {
        PluginConfig base = TestConfigs.base();
        ConfigDelta.Result result = ConfigDelta.diff(base, TestConfigs.base());
        assertFalse(result.hasChanges());
        assertTrue(result.changedSections().isEmpty());
        assertFalse(result.languageChanged());
    }

    @Test
    void languageChangeIsTheOnlyHotSection() {
        ConfigDelta.Result result = ConfigDelta.diff(
                TestConfigs.base(), TestConfigs.withLanguage(TestConfigs.base(), "ru"));
        assertEquals(List.of("general"), result.changedSections());
        assertTrue(result.languageChanged());
        assertEquals("en", result.previousLanguage());
        assertEquals("ru", result.newLanguage());
    }

    @Test
    void databaseChangeIsReportedButNotHot() {
        ConfigDelta.Result result = ConfigDelta.diff(
                TestConfigs.base(), TestConfigs.withPoolMaximum(TestConfigs.base(), 20));
        assertEquals(List.of("database"), result.changedSections());
        assertFalse(result.languageChanged());
    }

    @Test
    void mirageChangeIsReported() {
        ConfigDelta.Result result = ConfigDelta.diff(
                TestConfigs.base(), TestConfigs.withMirageFixedTime(TestConfigs.base(), 1000));
        assertEquals(List.of("mirage"), result.changedSections());
    }

    @Test
    void configVersionBumpIsReported() {
        ConfigDelta.Result result = ConfigDelta.diff(
                TestConfigs.withConfigVersion(TestConfigs.base(), 2),
                TestConfigs.withConfigVersion(TestConfigs.base(), 3));
        assertTrue(result.changedSections().contains("config-version"));
    }

    @Test
    void combinedChangesAreAllReportedInDeterministicOrder() {
        PluginConfig base = TestConfigs.base();
        PluginConfig changed = TestConfigs.withLanguage(
                TestConfigs.withPoolMaximum(TestConfigs.withConfigVersion(base, 3), 42), "ru");
        ConfigDelta.Result result = ConfigDelta.diff(base, changed);
        assertEquals(List.of("config-version", "general", "database"), result.changedSections());
    }
}

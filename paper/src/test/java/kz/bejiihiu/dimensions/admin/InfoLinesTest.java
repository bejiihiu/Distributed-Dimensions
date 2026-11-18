package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfoLinesTest {

    private static Messages messages() {
        Messages messages = new Messages(Messages.class.getClassLoader(), message -> { });
        messages.select("en");
        return messages;
    }

    private static AdminInfo healthyInfo() {
        return new AdminInfo(
                "1.0.0", "git-Paper-1.21.8 (MC: 1.21.8)", "HEALTHY", List.of(),
                "FILE", false, 2, false, -1, -1,
                "mysql", 10, 2, "dd_",
                List.of(new AdminInfo.DimensionRoute("minecraft:overworld", "overworld"),
                        new AdminInfo.DimensionRoute("minecraft:the_nether", "nether"),
                        new AdminInfo.DimensionRoute("minecraft:the_end", "end")),
                true, 3, 0,
                "husksync-api", "READY", true,
                true, false, true,
                3723_000L);
    }

    private static AdminInfo degradedInfo() {
        return new AdminInfo(
                "1.0.0", "git-Paper-1.21.8 (MC: 1.21.8)", "DEGRADED",
                List.of("persistence unavailable (fail-closed): connection refused"),
                "DEFAULTS_FALLBACK", true, 2, false, -1, -1,
                "mysql", 10, 2, "dd_",
                List.of(),
                true, 2, 1,
                "logging-fallback", "FAILED", true,
                true, true, true,
                61_000L);
    }

    private static List<String> texts(List<net.kyori.adventure.text.Component> lines) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        return lines.stream().map(serializer::serialize).toList();
    }

    @Test
    void healthyOutputCoversTheCoreFacts() {
        List<String> text = texts(InfoLines.build(healthyInfo(), messages()));
        String joined = String.join("\n", text);
        assertTrue(joined.contains("1.0.0"), "plugin version missing:\n" + joined);
        assertTrue(joined.contains("HEALTHY"), "health missing:\n" + joined);
        assertTrue(joined.contains("mysql"), "db engine missing:\n" + joined);
        assertTrue(joined.contains("pool=10/2"), "pool sizing missing:\n" + joined);
        assertTrue(joined.contains("minecraft:the_nether"), "dimension route missing:\n" + joined);
        assertTrue(joined.contains("nether"), "dimension server missing:\n" + joined);
        assertTrue(joined.contains("husksync-api"), "sync level missing:\n" + joined);
        assertTrue(joined.contains("READY"), "sync phase missing:\n" + joined);
        assertTrue(joined.contains("uptime: 1h 2m"), "uptime missing:\n" + joined);
    }

    @Test
    void databaseLineNeverLeaksTheUrl() {
        AdminInfo info = new AdminInfo(
                "1.0.0", "git-Paper-1.21.8 (MC: 1.21.8)", "HEALTHY", List.of(),
                "FILE", false, 2, false, -1, -1,
                "mysql", 10, 2, "dd_",
                List.of(),
                false, 0, 0,
                null, "FAILED", true,
                true, false, true,
                1_000L);
        String joined = String.join("\n", texts(InfoLines.build(info, messages())));
        assertFalse(joined.contains("jdbc"), "jdbc-url must never appear in /dd info:\n" + joined);
        assertFalse(joined.contains("3306"), "host/port must never appear in /dd info:\n" + joined);
        assertFalse(joined.contains("localhost"), "host must never appear in /dd info:\n" + joined);
    }

    @Test
    void degradedOutputCarriesTheReasons() {
        String joined = String.join("\n", texts(InfoLines.build(degradedInfo(), messages())));
        assertTrue(joined.contains("DEGRADED"));
        assertTrue(joined.contains("persistence unavailable"));
        assertTrue(joined.contains("DEFAULTS_FALLBACK"));
        assertTrue(joined.contains("FAILED"));
    }

    @Test
    void mirageDisabledAndSyncNotBoundAreReported() {
        AdminInfo info = new AdminInfo(
                "1.0.0", "git-Paper-1.21.8 (MC: 1.21.8)", "HEALTHY", List.of(),
                "FILE", false, 2, false, -1, -1,
                "postgresql", 10, 2, "dd_",
                List.of(),
                false, 0, 0,
                null, "UNAVAILABLE", true,
                true, false, false,
                1_000L);
        String joined = String.join("\n", texts(InfoLines.build(info, messages())));
        assertTrue(joined.contains("disabled"), "mirage disabled must be visible:\n" + joined);
        assertTrue(joined.contains("not bound"), "unbound sync must be visible:\n" + joined);
    }

    @Test
    void mirageFailuresAreCountedWhenTheRegistryIsDegraded() {
        AdminInfo base = healthyInfo();
        AdminInfo withFailures = new AdminInfo(base.pluginVersion(), base.serverVersion(), base.healthStatus(),
                base.healthReasons(), base.configSource(), base.configFileCreated(), base.configVersion(),
                base.configMigrated(), base.migratedFrom(), base.migratedTo(),
                base.databaseEngine(), base.poolMaximum(), base.poolMinimum(), base.tablePrefix(),
                base.dimensions(), base.mirageEnabled(), base.mirageTrackedWorlds(), 2,
                base.syncStrategy(), base.syncPhase(), base.syncWaitFor(),
                base.apiEnabled(), base.compatSyntheticEvents(), base.grantAdvancements(), base.uptimeMillis());
        String joined = String.join("\n", texts(InfoLines.build(withFailures, messages())));
        assertTrue(joined.contains("2"), "failure count must be visible:\n" + joined);
    }

    @Test
    void humanDurationFormatsDaysHoursMinutesSeconds() {
        assertEquals("0s", InfoLines.humanDuration(0));
        assertEquals("59s", InfoLines.humanDuration(59_000));
        assertEquals("1m 5s", InfoLines.humanDuration(65_000));
        assertEquals("1h 2m 3s", InfoLines.humanDuration(3723_000));
        assertEquals("1d 0h 0m 0s", InfoLines.humanDuration(86_400_000));
        assertEquals("3d 4h 54m 5s", InfoLines.humanDuration(276_845_000));
    }

    @Test
    void russianBundleRendersInfoToo() {
        Messages messages = new Messages(Messages.class.getClassLoader(), message -> { });
        messages.select("ru");
        String joined = String.join("\n", texts(InfoLines.build(healthyInfo(), messages)));
        assertTrue(joined.contains("здоровье: HEALTHY"), "ru health line missing:\n" + joined);
        assertTrue(joined.contains("измерения"), "ru dimensions label missing:\n" + joined);
    }
}

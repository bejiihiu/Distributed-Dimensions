package kz.bejiihiu.dimensions.paper.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StrategySelectionTest {

    // ------------------------------------------------------------------ auto

    @Test
    void autoPrefersHuskSyncApi() {
        StrategySelection.Decision d = StrategySelection.select("auto", true, true, "MC Data Bridge");
        assertEquals(StrategySelection.Strategy.HUSKSYNC_API, d.strategy());
    }

    @Test
    void autoFallsBackToReflectionWhenConfigured() {
        StrategySelection.Decision d = StrategySelection.select("auto", false, true, "InvSYNC");
        assertEquals(StrategySelection.Strategy.REFLECTION, d.strategy());
    }

    @Test
    void autoFallsBackToPresenceDelayWhenKnownPluginInstalled() {
        StrategySelection.Decision d = StrategySelection.select("auto", false, false, "MC Data Bridge");
        assertEquals(StrategySelection.Strategy.PRESENCE_DELAY, d.strategy());
    }

    @Test
    void autoFallsBackToDegradedWhenNothingDetected() {
        StrategySelection.Decision d = StrategySelection.select("auto", false, false, null);
        assertEquals(StrategySelection.Strategy.DEGRADED_FALLBACK, d.strategy());
    }

    @Test
    void autoIsCaseAndWhitespaceInsensitive() {
        StrategySelection.Decision d = StrategySelection.select("  Auto  ", false, false, null);
        assertEquals(StrategySelection.Strategy.DEGRADED_FALLBACK, d.strategy());
    }

    // ------------------------------------------------------------------ explicit pins

    @Test
    void husksyncPinBindsWhenApiAvailable() {
        StrategySelection.Decision d = StrategySelection.select("husksync", true, false, null);
        assertEquals(StrategySelection.Strategy.HUSKSYNC_API, d.strategy());
    }

    @Test
    void husksyncPinFailsLoudlyWhenUnavailable() {
        StrategySelection.Decision d = StrategySelection.select("husksync", false, true, "MC Data Bridge");
        assertEquals(StrategySelection.Strategy.LOUD_FAILURE, d.strategy());
    }

    @Test
    void reflectionPinBindsWhenConfigured() {
        StrategySelection.Decision d = StrategySelection.select("reflection", false, true, null);
        assertEquals(StrategySelection.Strategy.REFLECTION, d.strategy());
    }

    @Test
    void reflectionPinFailsLoudlyWhenUnconfigured() {
        StrategySelection.Decision d = StrategySelection.select("reflection", true, false, null);
        assertEquals(StrategySelection.Strategy.LOUD_FAILURE, d.strategy());
    }

    @Test
    void delayedPinBindsToPresentPlugin() {
        StrategySelection.Decision d = StrategySelection.select("delayed", false, false, "InvSYNC");
        assertEquals(StrategySelection.Strategy.PRESENCE_DELAY, d.strategy());
    }

    @Test
    void delayedPinDegradesWhenNothingDetected() {
        StrategySelection.Decision d = StrategySelection.select("delayed", false, false, null);
        assertEquals(StrategySelection.Strategy.DEGRADED_FALLBACK, d.strategy());
    }

    @Test
    void nonePinAlwaysDegrades() {
        assertEquals(StrategySelection.Strategy.DEGRADED_FALLBACK,
                StrategySelection.select("none", true, true, "MC Data Bridge").strategy());
    }

    @Test
    void unknownModeIsTreatedAsDegradedFallback() {
        StrategySelection.Decision d = StrategySelection.select("yeet", true, true, "MC Data Bridge");
        assertEquals(StrategySelection.Strategy.DEGRADED_FALLBACK, d.strategy());
    }

    @Test
    void nullModeIsTreatedAsAuto() {
        StrategySelection.Decision d = StrategySelection.select(null, true, false, null);
        assertEquals(StrategySelection.Strategy.HUSKSYNC_API, d.strategy());
    }

    // ------------------------------------------------------------------ known plugin detection

    @Test
    void findKnownSyncPluginMatchesCaseInsensitively() {
        assertEquals("invsync", StrategySelection.findKnownSyncPlugin(List.of("SomeOther", "invsync")));
        assertEquals("mc data bridge", StrategySelection.findKnownSyncPlugin(List.of("mc data bridge")));
    }

    @Test
    void findKnownSyncPluginReturnsNullWhenAbsent() {
        assertNull(StrategySelection.findKnownSyncPlugin(List.of("ProtocolLib", "WorldEdit")));
        assertNull(StrategySelection.findKnownSyncPlugin(List.of()));
        assertNull(StrategySelection.findKnownSyncPlugin(null));
    }

    @Test
    void findKnownSyncPluginTrimsWhitespace() {
        assertEquals("DataSynchronization", StrategySelection.findKnownSyncPlugin(List.of("  DataSynchronization  ")));
    }
}

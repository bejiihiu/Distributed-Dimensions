package kz.bejiihiu.dimensions.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Health accounting for the backend: healthy vs degraded, invariants of the record. */
class HealthReportTest {

    @Test
    void healthyCarriesNoReasons() {
        HealthReport report = HealthReport.healthy();
        assertEquals(HealthReport.Status.HEALTHY, report.status());
        assertTrue(report.reasons().isEmpty());
        assertTrue(report.isHealthy());
    }

    @Test
    void degradedRequiresAtLeastOneReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthReport(HealthReport.Status.DEGRADED, List.of()));
    }

    @Test
    void degradedCarriesReasonsAndReportsUnhealthy() {
        HealthReport report = new HealthReport(HealthReport.Status.DEGRADED, List.of("db down"));
        assertFalse(report.isHealthy());
        assertEquals(List.of("db down"), report.reasons());
    }

    @Test
    void reasonsAreCopiedDefensivelyAndUnmodifiable() {
        List<String> mutable = new java.util.ArrayList<>();
        mutable.add("db down");
        HealthReport report = new HealthReport(HealthReport.Status.DEGRADED, mutable);
        mutable.add("something else");
        assertEquals(1, report.reasons().size());
        assertThrows(UnsupportedOperationException.class, () -> report.reasons().add("x"));
    }

    @Test
    void nullReasonsAreToleratedAsHealthyOnlyForHealthyStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthReport(HealthReport.Status.DEGRADED, null));
    }
}

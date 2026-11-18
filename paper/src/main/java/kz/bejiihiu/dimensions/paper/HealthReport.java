package kz.bejiihiu.dimensions.paper;

/**
 * Overall health of the backend plugin as seen by later waves (portal and transfer
 * flows refuse to operate unless healthy).
 *
 * <p>{@link Status#DEGRADED} means: the plugin stays enabled, but at least one
 * subsystem failed to start and the failure is not safe to route around — the
 * reasons list carries human-readable diagnostics. Transfers must be refused
 * while degraded. A DEGRADED state never implies a half-open service: the
 * persistence journal and portal index throw when unavailable instead of
 * returning a never-migrated or half-initialised instance.
 */
public record HealthReport(Status status, java.util.List<String> reasons) {

    public enum Status {
        HEALTHY,
        DEGRADED
    }

    public HealthReport {
        reasons = java.util.List.copyOf(reasons == null ? java.util.List.of() : reasons);
        if (status == Status.DEGRADED && reasons.isEmpty()) {
            throw new IllegalArgumentException("a degraded report requires at least one reason");
        }
    }

    public static HealthReport healthy() {
        return new HealthReport(Status.HEALTHY, java.util.List.of());
    }

    /** True only when every subsystem initialised cleanly. */
    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
}

package kz.bejiihiu.dimensions.paper;

import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.DimensionId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the configured routing targets ({@code network.dimensions}) from a
 * dimension to its proxy server name.
 *
 * <p>The resolver never throws on configuration mistakes. Grammar of servers and
 * dimension keys is enforced by the configuration records; everything that can still
 * be wrong is surfaced as data instead: {@link #errors()} lists fatal mis-wirings
 * (a slot configured for another dimension's canonical key, after which the slot is
 * not resolvable), {@link #warnings()} lists suspicious-but-legal shapes (several
 * slots on the same server). A slot whose canonical key is mis-wired does not route,
 * so later waves refuse to transfer instead of sending players to the wrong backend.
 */
public final class DimensionResolver {

    private final Map<DimensionId, DimensionTarget> routes;
    private final List<String> errors;
    private final List<String> warnings;

    private DimensionResolver(Map<DimensionId, DimensionTarget> routes,
            List<String> errors, List<String> warnings) {
        this.routes = routes;
        this.errors = errors;
        this.warnings = warnings;
    }

    /** Build the resolver from the loaded configuration. Never throws. */
    public static DimensionResolver from(DimensionsSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Map<DimensionId, DimensionTarget> routes = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        addSlot("overworld", settings.overworld(), DimensionId.OVERWORLD, routes, errors);
        addSlot("nether", settings.nether(), DimensionId.NETHER, routes, errors);
        addSlot("end", settings.end(), DimensionId.THE_END, routes, errors);

        List<String> warnings = new ArrayList<>();
        duplicateServers(settings).ifPresent(warnings::add);

        return new DimensionResolver(Collections.unmodifiableMap(routes),
                Collections.unmodifiableList(errors), Collections.unmodifiableList(warnings));
    }

    private static void addSlot(String slot, DimensionTarget target, DimensionId canonical,
            Map<DimensionId, DimensionTarget> routes, List<String> errors) {
        DimensionId configured = DimensionId.of(target.dimension());
        if (!configured.equals(canonical)) {
            errors.add("network.dimensions." + slot + " serves '" + configured
                    + "' but the " + slot + " slot must route '" + canonical
                    + "' — transfers for the " + slot + " are refused until this is fixed");
        }
        // Keyed by the configured key, not the canonical one: a mis-wired slot is simply
        // not resolvable under the canonical id, which is the fail-closed behaviour.
        routes.put(configured, target);
    }

    private static Optional<String> duplicateServers(DimensionsSettings settings) {
        List<String> servers = List.of(
                settings.overworld().server(), settings.nether().server(), settings.end().server());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String server : servers) {
            counts.merge(server, 1, Integer::sum);
        }
        List<String> duplicates = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        return duplicates.isEmpty()
                ? Optional.empty()
                : Optional.of("several dimension slots route to the same server(s) "
                        + String.join(", ", duplicates) + " — the cross-server split is inert");
    }

    /** Immutable routes keyed by configured dimension id. */
    public Map<DimensionId, DimensionTarget> routes() {
        return routes;
    }

    /** Resolvable targets, as values — for boot logging. */
    public Collection<DimensionTarget> targets() {
        return routes.values();
    }

    /** Mis-wirings that degrade the backend. Empty in a correct configuration. */
    public List<String> errors() {
        return errors;
    }

    /** Suspicious but legal shapes; logged, never degradable on their own. */
    public List<String> warnings() {
        return warnings;
    }

    /** Exact lookup by the configured dimension id. */
    public Optional<DimensionTarget> route(DimensionId dimension) {
        return Optional.ofNullable(routes.get(dimension));
    }

    /** Server name for a dimension, if routed. */
    public Optional<String> serverFor(DimensionId dimension) {
        return route(dimension).map(DimensionTarget::server);
    }

    /** True when all three vanilla slots resolve under their canonical keys. */
    public boolean allCanonicalResolved() {
        return routes.containsKey(DimensionId.OVERWORLD)
                && routes.containsKey(DimensionId.NETHER)
                && routes.containsKey(DimensionId.THE_END);
    }
}

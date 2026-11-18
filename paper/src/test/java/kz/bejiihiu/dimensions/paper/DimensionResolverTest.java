package kz.bejiihiu.dimensions.paper;

import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.DimensionId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation and lookup semantics of the dimension routing table. */
class DimensionResolverTest {

    private static DimensionsSettings canonical() {
        return new DimensionsSettings(
                new DimensionTarget("overworld", "minecraft:overworld"),
                new DimensionTarget("nether", "minecraft:the_nether"),
                new DimensionTarget("end", "minecraft:the_end"));
    }

    @Test
    void canonicalConfigResolvesAllThreeSlotsWithoutErrors() {
        DimensionResolver resolver = DimensionResolver.from(canonical());
        assertTrue(resolver.errors().isEmpty());
        assertTrue(resolver.warnings().isEmpty());
        assertTrue(resolver.allCanonicalResolved());
        assertEquals("nether", resolver.serverFor(DimensionId.NETHER).orElseThrow());
        assertEquals("overworld", resolver.serverFor(DimensionId.OVERWORLD).orElseThrow());
        assertEquals("end", resolver.serverFor(DimensionId.THE_END).orElseThrow());
        assertEquals(3, resolver.routes().size());
        assertEquals(3, resolver.targets().size());
    }

    @Test
    void miswiredSlotFailsClosedInsteadOfMisrouting() {
        DimensionsSettings miswired = new DimensionsSettings(
                new DimensionTarget("overworld", "minecraft:the_end"),
                new DimensionTarget("nether", "minecraft:the_nether"),
                new DimensionTarget("end", "minecraft:the_end"));
        DimensionResolver resolver = DimensionResolver.from(miswired);
        assertEquals(1, resolver.errors().size());
        assertTrue(resolver.errors().get(0).contains("network.dimensions.overworld"));
        assertEquals("", resolver.serverFor(DimensionId.OVERWORLD).orElse(""));
        // The canonical end route must survive the key collision (end is added after overworld)
        assertEquals("end", resolver.serverFor(DimensionId.THE_END).orElseThrow());
        assertEquals("nether", resolver.serverFor(DimensionId.NETHER).orElseThrow());
        assertEquals(false, resolver.allCanonicalResolved());
    }

    @Test
    void duplicateServersAreAWarningNotAnError() {
        DimensionsSettings duplicate = new DimensionsSettings(
                new DimensionTarget("same", "minecraft:overworld"),
                new DimensionTarget("same", "minecraft:the_nether"),
                new DimensionTarget("same", "minecraft:the_end"));
        DimensionResolver resolver = DimensionResolver.from(duplicate);
        assertTrue(resolver.errors().isEmpty());
        assertEquals(1, resolver.warnings().size());
        assertTrue(resolver.warnings().get(0).contains("same"));
    }

    @Test
    void unknownDimensionDoesNotResolveAndIsNotAnError() {
        DimensionResolver resolver = DimensionResolver.from(canonical());
        assertTrue(resolver.route(DimensionId.of("minecraft:custom_thing")).isEmpty());
        assertTrue(resolver.errors().isEmpty());
    }

    @Test
    void exposedCollectionsAreUnmodifiable() {
        DimensionResolver resolver = DimensionResolver.from(canonical());
        assertThrows(UnsupportedOperationException.class,
                () -> resolver.routes().put(DimensionId.of("minecraft:x"),
                        new DimensionTarget("x", "minecraft:x")));
        assertThrows(UnsupportedOperationException.class,
                () -> resolver.errors().add("bogus"));
        assertThrows(UnsupportedOperationException.class,
                () -> resolver.warnings().add("bogus"));
    }

    @Test
    void routeLookupUsesConfiguredKey() {
        DimensionResolver resolver = DimensionResolver.from(canonical());
        assertEquals("nether",
                resolver.route(DimensionId.NETHER).map(DimensionTarget::server).orElseThrow());
    }
}

package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.paper.TestWorlds;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Selection rules for the arrival respawn world: the world hosting the backend's own
 * resident dimension wins over the platform respawn world, mirages are never eligible,
 * and everything degrades to the pre-existing chain when nothing fits.
 */
class RespawnWorldSelectionTest {

    private static final Predicate<World> NO_MIRAGES = world -> false;

    private static Predicate<World> mirages(String... names) {
        List<String> mirageNames = Arrays.asList(names);
        return world -> mirageNames.contains(world.getName());
    }

    // ------------------------------------------------------------ own environment wins

    @Test
    void prefersResidentNetherlandsWorldOverStrayOverworld() {
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);
        World resident = TestWorlds.create("world_nether", Environment.NETHER);
        World end = TestWorlds.create("world_the_end", Environment.THE_END);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel, resident, end), Environment.NETHER, NO_MIRAGES, strayLevel);

        assertEquals(resident, selected);
    }

    @Test
    void prefersResidentEndWorld() {
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);
        World resident = TestWorlds.create("world_the_end", Environment.THE_END);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel, resident), Environment.THE_END, NO_MIRAGES, strayLevel);

        assertEquals(resident, selected);
    }

    @Test
    void prefersResidentOverworldWorldOnOverworldBackend() {
        World resident = TestWorlds.create("world", Environment.NORMAL);

        World selected = RespawnWorldSelection.resolve(
                List.of(resident), Environment.NORMAL, NO_MIRAGES, resident);

        assertEquals(resident, selected);
    }

    @Test
    void miragesNeverWinEvenWhenTheirEnvironmentMatches() {
        World resident = TestWorlds.create("world_nether", Environment.NETHER);
        World stub = TestWorlds.create("world_nether_stub", Environment.NETHER);

        World selected = RespawnWorldSelection.resolve(
                List.of(stub, resident), Environment.NETHER, mirages("world_nether_stub"), stub);

        assertEquals(resident, selected);
    }

    // ------------------------------------------------------------ mirage-only / unmatched

    @Test
    void stemDisabledEverywhereFallsBackToPlatformRespawnWorld() {
        // mirage-only nether backend: the NETHER stub matches the environment but is not eligible
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);
        World stub = TestWorlds.create("world_nether", Environment.NETHER);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel, stub), Environment.NETHER, mirages("world_nether"), strayLevel);

        assertEquals(strayLevel, selected);
    }

    @Test
    void noMatchingEnvironmentFallsBackToPlatformRespawnWorld() {
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel), Environment.NETHER, NO_MIRAGES, strayLevel);

        assertEquals(strayLevel, selected);
    }

    @Test
    void noMatchAndNullRespawnWorldYieldsFirstLoadedWorld() {
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel), Environment.NETHER, NO_MIRAGES, null);

        assertEquals(strayLevel, selected);
    }

    @Test
    void noMatchNullRespawnWorldAndNoWorldsYieldsNull() {
        assertNull(RespawnWorldSelection.resolve(List.of(), Environment.NETHER, NO_MIRAGES, null));
    }

    // ------------------------------------------------------------ unresolvable own slot

    @Test
    void unresolvableOwnEnvironmentUsesPlatformRespawnWorld() {
        World strayLevel = TestWorlds.create("world", Environment.NORMAL);
        World resident = TestWorlds.create("world_nether", Environment.NETHER);

        World selected = RespawnWorldSelection.resolve(
                List.of(strayLevel, resident), null, NO_MIRAGES, strayLevel);

        assertEquals(strayLevel, selected);
    }

    @Test
    void unresolvableOwnEnvironmentWithNullRespawnYieldsFirstLoadedWorld() {
        World first = TestWorlds.create("world", Environment.NORMAL);
        World resident = TestWorlds.create("world_nether", Environment.NETHER);

        World selected = RespawnWorldSelection.resolve(
                List.of(first, resident), null, NO_MIRAGES, null);

        assertEquals(first, selected);
    }

    @Test
    void mirageOnlyBackendReturnsPlatformRespawnWorldVerbatim() {
        // the exclusion applies to the own-environment pick only; the legacy fallback is
        // today's behaviour and hands out the platform respawn world untouched
        World stub = TestWorlds.create("world_nether", Environment.NETHER);

        World selected = RespawnWorldSelection.resolve(
                List.of(stub), Environment.NETHER, mirages("world_nether"), stub);

        assertEquals(stub, selected);
    }

    // ------------------------------------------------------------ defensive supplier read

    @Test
    void safeGetReturnsNullWhenSupplierThrows() {
        assertNull(RespawnWorldSelection.safeGet(() -> {
            throw new IllegalStateException("broken wiring");
        }));
    }
}

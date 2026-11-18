package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link RespawnPointStore} contract, engine-agnostic. Subclasses provide a fresh,
 * empty store (and optionally cleanup) per test; everything asserted here must hold for
 * every engine — this is the definition of the interface, not a convenience suite.
 */
abstract class RespawnPointStoreContractTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T0.plusSeconds(9);

    /** A fresh, empty store for each test. */
    protected abstract RespawnPointStore openStore();

    /** Called after each test; default no-op (e.g. in-memory). */
    protected void closeStore() {
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        closeStore();
    }

    private static RespawnPointRecord sample(UUID player, DimensionId dimension,
                                             String world, RespawnKind kind, Instant at) {
        return new RespawnPointRecord(player, dimension, world,
                new Position(10.5, 64.0, -20.25, 90.0, -15.0), kind, at);
    }

    @Test
    void findMissingReturnsEmpty() {
        RespawnPointStore store = openStore();
        assertEquals(Optional.empty(), store.find(UUID.randomUUID()));
    }

    @Test
    void upsertInsertsAndRoundTrips() {
        RespawnPointStore store = openStore();
        UUID player = UUID.randomUUID();
        RespawnPointRecord point = sample(player, DimensionId.NETHER, "world_nether",
                RespawnKind.RESPAWN_ANCHOR, T0);
        store.upsert(point);
        assertEquals(Optional.of(point), store.find(player));
    }

    @Test
    void upsertReplacesTheExistingRow() {
        RespawnPointStore store = openStore();
        UUID player = UUID.randomUUID();
        RespawnPointRecord first = sample(player, DimensionId.OVERWORLD, "world", RespawnKind.BED, T0);
        store.upsert(first);

        RespawnPointRecord moved = new RespawnPointRecord(player, DimensionId.NETHER, "world_nether",
                new Position(-100.5, 70.0, 200.25, 180.0, 0.0), RespawnKind.RESPAWN_ANCHOR, T2);
        store.upsert(moved);

        assertEquals(Optional.of(moved), store.find(player));
        assertEquals(RespawnKind.RESPAWN_ANCHOR, store.find(player).orElseThrow().kind());
        assertEquals(Instant.ofEpochMilli(T2.toEpochMilli()), store.find(player).orElseThrow().updatedAt());
    }

    @Test
    void upsertIsIndependentPerPlayer() {
        RespawnPointStore store = openStore();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        RespawnPointRecord a = sample(alice, DimensionId.OVERWORLD, "world", RespawnKind.BED, T0);
        RespawnPointRecord b = sample(bob, DimensionId.THE_END, "world_the_end", RespawnKind.RESPAWN_ANCHOR, T1);
        store.upsert(a);
        store.upsert(b);

        // replacing one must not touch the other
        RespawnPointRecord moved = sample(alice, DimensionId.NETHER, "world_nether", RespawnKind.RESPAWN_ANCHOR, T2);
        store.upsert(moved);

        assertEquals(Optional.of(moved), store.find(alice));
        assertEquals(Optional.of(b), store.find(bob));
    }

    @Test
    void deleteRemovesExactlyTheGivenPlayer() {
        RespawnPointStore store = openStore();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.upsert(sample(alice, DimensionId.OVERWORLD, "world", RespawnKind.BED, T0));
        store.upsert(sample(bob, DimensionId.NETHER, "world_nether", RespawnKind.RESPAWN_ANCHOR, T1));

        assertTrue(store.delete(alice));
        assertEquals(Optional.empty(), store.find(alice));
        assertEquals(Optional.of(sample(bob, DimensionId.NETHER, "world_nether", RespawnKind.RESPAWN_ANCHOR, T1)),
                store.find(bob));
        assertFalse(store.delete(alice));
    }

    @Test
    void negativeCoordinatesSurviveRoundTrip() {
        RespawnPointStore store = openStore();
        UUID player = UUID.randomUUID();
        RespawnPointRecord point = new RespawnPointRecord(player, DimensionId.OVERWORLD, "world",
                new Position(-30010.75, -32.0, -12800.125, -180.0, 45.0), RespawnKind.BED, T0);
        store.upsert(point);
        RespawnPointRecord read = store.find(player).orElseThrow();
        assertEquals(-30010.75, read.position().x());
        assertEquals(-32.0, read.position().y());
        assertEquals(-12800.125, read.position().z());
        assertEquals(-180.0, read.position().yaw());
        assertEquals(45.0, read.position().pitch());
    }

    @Test
    void worldSpawnKindIsRejectedByTheRecord() {
        assertThrows(IllegalArgumentException.class, () -> new RespawnPointRecord(
                UUID.randomUUID(), DimensionId.OVERWORLD, "world",
                new Position(0, 64, 0), RespawnKind.WORLD_SPAWN, T0));
    }

    @Test
    void blankWorldIsRejectedByTheRecord() {
        assertThrows(IllegalArgumentException.class, () -> new RespawnPointRecord(
                UUID.randomUUID(), DimensionId.OVERWORLD, "  ",
                new Position(0, 64, 0), RespawnKind.BED, T0));
    }

    @Test
    void oversizedWorldIsRejectedByTheRecord() {
        assertThrows(IllegalArgumentException.class, () -> new RespawnPointRecord(
                UUID.randomUUID(), DimensionId.OVERWORLD, "w".repeat(129),
                new Position(0, 64, 0), RespawnKind.BED, T0));
    }

    @Test
    void customDimensionKeysRoundTrip() {
        RespawnPointStore store = openStore();
        UUID player = UUID.randomUUID();
        DimensionId custom = DimensionId.of("mymod:fragments/void_valley");
        RespawnPointRecord point = sample(player, custom, "custom_world", RespawnKind.RESPAWN_ANCHOR, T0);
        store.upsert(point);
        assertEquals(Optional.of(point), store.find(player));
    }
}

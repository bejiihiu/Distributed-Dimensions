package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Minecart;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vanilla portal-cooldown semantics for the player entity itself. */
class PlayerPortalCooldownsTest {

    private static final long WINDOW_MILLIS = PortalCooldownRules.ORDINARY_MILLIS;

    private static org.bukkit.entity.Player standalonePlayer(java.util.UUID uuid, org.bukkit.Location location) {
        return kz.bejiihiu.dimensions.paper.FakePlayers.create(uuid, location, true);
    }

    private static Minecart fakeMinecart() {
        return kz.bejiihiu.dimensions.paper.TestVehicles.minecart();
    }

    private static org.bukkit.entity.Player ridingPlayer(UUID uuid, org.bukkit.Location location,
                                                         Minecart vehicle) {
        return kz.bejiihiu.dimensions.paper.FakePlayers.create(uuid, location, true, vehicle);
    }

    @Test
    void stampThenCoolingDownWithinTheWindow() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        var world = kz.bejiihiu.dimensions.paper.TestWorlds.create("world", org.bukkit.World.Environment.NORMAL);
        org.bukkit.Location location = new org.bukkit.Location(world, 0.0, 64.0, 0.0);
        org.bukkit.entity.Player player = standalonePlayer(UUID.randomUUID(), location);
        UUID op = UUID.randomUUID();

        PlayerPortalCooldowns.stampArrival(store, player, op, 1_000_000L);

        assertEquals(1_000_000L, store.lastPassMillis(player));
        assertEquals(op, store.lastOperationId(player));
        assertTrue(PlayerPortalCooldowns.isCoolingDown(store, player, 1_000_000L + WINDOW_MILLIS - 1));
        assertTrue(PlayerPortalCooldowns.isCoolingDown(store, player, 1_000_000L));
        assertFalse(PlayerPortalCooldowns.isCoolingDown(store, player, 1_000_000L + WINDOW_MILLIS),
                "the exact window boundary is no longer cooling down");
        assertFalse(PlayerPortalCooldowns.isCoolingDown(store, player, 1_000_000L + WINDOW_MILLIS + 1));
    }

    @Test
    void neverStampedIsNeverCoolingDown() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        var world = kz.bejiihiu.dimensions.paper.TestWorlds.create("world", org.bukkit.World.Environment.NORMAL);
        org.bukkit.entity.Player player = standalonePlayer(
                UUID.randomUUID(), new org.bukkit.Location(world, 0.0, 64.0, 0.0));
        assertFalse(PlayerPortalCooldowns.isCoolingDown(store, player, Long.MAX_VALUE));
    }

    @Test
    void riderIsExemptFromCheckAndStamp() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        var world = kz.bejiihiu.dimensions.paper.TestWorlds.create("world", org.bukkit.World.Environment.NORMAL);
        Minecart vehicle = fakeMinecart();
        org.bukkit.entity.Player player = ridingPlayer(
                UUID.randomUUID(), new org.bukkit.Location(world, 0.0, 64.0, 0.0), vehicle);

        PlayerPortalCooldowns.stampArrival(store, player, UUID.randomUUID(), 1_000_000L);
        assertNull(store.lastPassMillis(player), "a player riding a vehicle never cools down");
        assertFalse(PlayerPortalCooldowns.isCoolingDown(store, player, 1_000_000L));
    }

    @Test
    void ordinaryPlayersCoolDownAsOrdinaryEntities() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        var world = kz.bejiihiu.dimensions.paper.TestWorlds.create("world", org.bukkit.World.Environment.NORMAL);
        org.bukkit.entity.Player player = standalonePlayer(
                UUID.randomUUID(), new org.bukkit.Location(world, 0.0, 64.0, 0.0));
        PlayerPortalCooldowns.stampArrival(store, player, UUID.randomUUID(), 0L);
        // vanilla: players behave like ordinary entities — not projectiles, not vehicles
        assertEquals(PortalCooldownRules.ORDINARY_MILLIS, PortalCooldownRules.cooldownMillis(player));
        assertTrue(PlayerPortalCooldowns.isCoolingDown(store, player, WINDOW_MILLIS - 1));
    }
}

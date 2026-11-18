package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyGraphTest {

    private final ProxyEntities.Canned zombieCanned = new ProxyEntities.Canned("zombie")
            .answer("getPassengers", List.of());
    private final ProxyEntities.Canned itemCanned = new ProxyEntities.Canned("item")
            .answer("getPassengers", List.of());
    private final ProxyEntities.Canned playerCanned = new ProxyEntities.Canned("player")
            .answer("getPassengers", List.of());

    private Zombie zombie() {
        zombieCanned.answer("getPassengers", List.of());
        return ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, zombieCanned);
    }

    private Player player() {
        return ProxyEntities.entity(Player.class, EntityType.PLAYER, playerCanned);
    }

    private Item item() {
        return ProxyEntities.entity(Item.class, EntityType.ITEM, itemCanned);
    }

    private Minecart minecartWith(List<Entity> passengers) {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("minecart");
        canned.answer("getPassengers", passengers);
        return ProxyEntities.entity(Minecart.class, EntityType.MINECART, canned);
    }

    @Test
    void graphComesOutPreOrderRootFirstPassengersInMountOrder() {
        ProxyEntities.Canned pig = new ProxyEntities.Canned("pig").answer("getPassengers", List.of());
        Zombie passengerA = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, pig);
        Item passengerB = item();
        Minecart root = minecartWith(List.of(passengerA, passengerB));

        EntityTransferMetrics metrics = new EntityTransferMetrics();
        List<EntityNode> graph = AssemblyGraph.build(root, metrics);
        assertEquals(3, graph.size());
        assertEquals(EntityPayloadKind.MINECART, graph.get(0).kind());
        assertEquals(-1, graph.get(0).parentSeq());
        assertEquals(0, graph.get(0).depth());
        assertEquals(EntityPayloadKind.MOB, graph.get(1).kind());
        assertEquals(0, graph.get(1).parentSeq());
        assertEquals(1, graph.get(1).depth());
        assertEquals(EntityPayloadKind.ITEM, graph.get(2).kind());
        assertEquals(0, graph.get(2).parentSeq());
        assertEquals(1, graph.get(2).depth());
        assertEquals("minecraft:minecart", graph.get(0).entityTypeKey());
        assertEquals("minecraft:zombie", graph.get(1).entityTypeKey());
    }

    @Test
    void passengerOrderIsPreservedAcrossDepth() {
        ProxyEntities.Canned cow = new ProxyEntities.Canned("cow").answer("getPassengers", List.of());
        Zombie inner = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, cow);
        ProxyEntities.Canned pig = new ProxyEntities.Canned("pig").answer("getPassengers", List.of(inner));
        Zombie middle = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, pig);
        Item outer = item();
        Minecart root = minecartWith(List.of(middle, outer));

        List<EntityNode> graph = AssemblyGraph.build(root, new EntityTransferMetrics());
        assertEquals(4, graph.size());
        assertEquals(List.of("minecraft:minecart", "minecraft:zombie", "minecraft:zombie", "minecraft:item"),
                graph.stream().map(EntityNode::entityTypeKey).toList());
        assertEquals(0, graph.get(1).parentSeq());
        assertEquals(1, graph.get(2).parentSeq());
        assertEquals(0, graph.get(3).parentSeq());
    }

    @Test
    void playersAreDroppedAndCountedNotSerialized() {
        Player rider = player();
        Minecart root = minecartWith(List.of(rider));
        EntityTransferMetrics metrics = new EntityTransferMetrics();
        List<EntityNode> graph = AssemblyGraph.build(root, metrics);
        assertEquals(1, graph.size(), "the player is not part of the payload");
        assertEquals(EntityPayloadKind.MINECART, graph.get(0).kind());
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.PLAYER_DROPPED).longValue());
    }

    @Test
    void nestedPlayerInPassengerSubtreeIsDropped() {
        ProxyEntities.Canned pig = new ProxyEntities.Canned("pig");
        Player hidden = player();
        pig.answer("getPassengers", List.of(hidden));
        Zombie middle = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, pig);
        Minecart root = minecartWith(List.of(middle));

        EntityTransferMetrics metrics = new EntityTransferMetrics();
        List<EntityNode> graph = AssemblyGraph.build(root, metrics);
        assertEquals(2, graph.size());
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.PLAYER_DROPPED).longValue());
    }

    @Test
    void assemblyReportsCarryingPlayerThroughTheWholeGraph() {
        Player nested = player();
        ProxyEntities.Canned pig = new ProxyEntities.Canned("pig").answer("getPassengers", List.of(nested));
        Zombie middle = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, pig);
        Item outer = item();
        Minecart root = minecartWith(List.of(middle, outer));
        assertTrue(AssemblyGraph.assemblyCarriesPlayer(root));
        assertTrue(AssemblyGraph.assemblyCarriesPlayer(middle));
        assertTrue(!AssemblyGraph.assemblyCarriesPlayer(outer));
    }

    @Test
    void witherAnywhereInTheGraphRefusesTheOperation() {
        ProxyEntities.Canned witherCanned = new ProxyEntities.Canned("wither").answer("getPassengers", List.of());
        Entity wither = ProxyEntities.entity(LivingEntity.class, EntityType.WITHER, witherCanned);
        Minecart root = minecartWith(List.of(wither));
        assertThrows(ExcludedEntityException.class,
                () -> AssemblyGraph.build(root, new EntityTransferMetrics()));
    }

    @Test
    void enderDragonRefuses() {
        ProxyEntities.Canned dragonCanned = new ProxyEntities.Canned("dragon").answer("getPassengers", List.of());
        Entity dragon = ProxyEntities.entity(LivingEntity.class, EntityType.ENDER_DRAGON, dragonCanned);
        assertThrows(ExcludedEntityException.class,
                () -> AssemblyGraph.build(dragon, new EntityTransferMetrics()));
    }

    @Test
    void creakingRefuses() {
        ProxyEntities.Canned creakingCanned = new ProxyEntities.Canned("creaking").answer("getPassengers", List.of());
        LivingEntity creaking = ProxyEntities.entity(LivingEntity.class, EntityType.CREAKING, creakingCanned);
        assertThrows(ExcludedEntityException.class,
                () -> AssemblyGraph.build(creaking, new EntityTransferMetrics()));
    }

    @Test
    void cyclesInACorruptGraphAreCutNotInfinite() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("cycle");
        canned.answer("getPassengers", List.of());
        Entity a = ProxyEntities.entity(Item.class, EntityType.ITEM, canned);
        // hand-build the cycle: a -> b -> a (corrupt reference data should not loop forever)
        ProxyEntities.Canned bCanned = new ProxyEntities.Canned("cycle-b");
        bCanned.answer("getPassengers", List.of(a));
        Entity b = ProxyEntities.entity(Item.class, EntityType.ITEM, bCanned);
        canned.answer("getPassengers", List.of(b));
        List<EntityNode> graph = AssemblyGraph.build(a, new EntityTransferMetrics());
        assertEquals(2, graph.size());
    }
}
